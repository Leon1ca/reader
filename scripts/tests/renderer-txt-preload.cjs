/* Real protocol-1 TXT host: trusted wheel creates a genuine in-flight preload. */
const {chromium}=require('playwright')
const fs=require('fs'),path=require('path'),http=require('http'),crypto=require('crypto'),assert=require('assert/strict')
const assets=path.resolve(__dirname,'../../app/src/main/assets/reader')
const allowed=['host.html','host.js','viewport.js','input.js','vendor/foliate/paginator.js']
const text=Array.from({length:100},(_,n)=>`<p><span data-block="${n}" data-length="100">${('ORIGINAL SOURCE '+n+' 中文 ').repeat(8)}</span></p>`).join('')
const docs=[`<!doctype html><html><body>${text}</body></html>`,`<!doctype html><html><body><p><span data-block="200" data-length="24">TARGET PRELOADED SOURCE</span></p></body></html>`]
const requests=new Map()
const server=http.createServer((req,res)=>{
  const match=/^\/reader\/([^/]+)\/revision\/(.*)$/.exec(req.url)
  if(!match){res.writeHead(404);res.end();return}
  const state=requests.get(match[1]),file=match[2],section=/^sections\/(\d+)\.html$/.exec(file)
  if(section){
    const i=Number(section[1])
    if(i===1){state.held.push(res);state.requested();return}
    res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});res.end(docs[i]);return
  }
  if(!allowed.includes(file)){res.writeHead(403);res.end();return}
  res.writeHead(200,{'Content-Type':file.endsWith('.js')?'text/javascript':'text/html'});res.end(fs.readFileSync(path.join(assets,file)))
})
;(async()=>{
  await new Promise(r=>server.listen(0,'127.0.0.1',r))
  const browser=await chromium.launch({headless:true,executablePath:process.env.READER_CHROMIUM}),results=[]
  try {
    for(const mode of ['loaded','superseded','closed','missing','timeout']) {
      const state={held:[]};state.requestReceived=new Promise(resolve=>state.requested=resolve);requests.set(mode,state)
      const page=await browser.newPage({viewport:{width:369,height:576}}),packets=[],errors=[]
      await page.exposeFunction('receive',raw=>packets.push(JSON.parse(raw)));await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.receive(raw)});page.on('pageerror',e=>errors.push(String(e)))
      const command=(id,type,payload)=>({protocol:1,sessionId:mode,revision:'revision',layoutEpoch:id,commandId:id,type,payload})
      const send=(id,type,payload)=>page.evaluate(c=>readerHost.command(c),command(id,type,payload))
      const terminal=id=>page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&['ready','error'].includes(e.type)),id,{timeout:15000})
      const release=()=>{for(const res of state.held){res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});res.end(mode==='missing'?'<!doctype html><html><body><p>Real source without the requested block</p></body></html>':docs[1])}state.held=[]}
      try {
        await page.goto(`http://127.0.0.1:${server.address().port}/reader/${mode}/revision/host.html`);await page.waitForFunction(()=>window.readerHost)
        await send(1,'open',{profileId:'preload',flow:'scrolled',sectionCount:2,target:{section:0,blockId:'0',character:0},geometry:{gap:'7%','margin-top':'24px','margin-bottom':'24px','margin-left':'16px','margin-right':'16px','max-column-count':1},beforeAuthor:'html,body{margin:0}body{font:20px serif}p{margin:1em 0;line-height:1.6}',afterAuthor:''})
        await terminal(1);assert(packets.some(e=>e.commandId===1&&e.type==='ready'))
        await page.mouse.move(180,300);await page.mouse.wheel(0,100000)
        await page.waitForFunction(()=>document.getElementById('renderer').getContents().some(c=>c.index===1&&c.doc.URL==='about:blank'&&!c.doc.querySelector('[data-block]')),null,{timeout:6000})
        let requestTimer;try {await Promise.race([state.requestReceived,new Promise((_,reject)=>requestTimer=setTimeout(()=>reject(Error('Controlled preload HTTP request was not received')),6000))])} finally {clearTimeout(requestTimer)}
        assert(state.held.length>0)
        await send(2,'goTo',{target:{section:1,blockId:'200',character:0}})
        // Two actual frames prove the request cannot synchronously resolve against about:blank.
        await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))))
        assert(!packets.some(e=>e.commandId===2&&['ready','error'].includes(e.type)))
        if(mode==='superseded')await send(3,'goTo',{target:{section:0,blockId:'0',character:0}})
        if(mode==='closed') {
          await page.evaluate(()=>readerHost.close());const count=packets.length;release()
          await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))))
          assert.equal(packets.length,count);assert.equal(await page.evaluate(()=>document.getElementById('renderer').getContents().length),0)
        } else {
          if(mode!=='timeout')release()
          const latest=mode==='superseded'?3:2;await terminal(latest)
          const events=packets.filter(e=>e.commandId===latest)
          if(mode==='missing'||mode==='timeout') {
            const error=events.find(e=>e.type==='error');assert(error)
            assert.match(error.message,mode==='missing'?/Missing stable content block/:/timed out/)
            assert(!/TARGET PRELOADED SOURCE|ORIGINAL SOURCE|中文/.test(JSON.stringify(error)))
            const count=packets.length;release()
            await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))))
            assert(!packets.slice(count).some(e=>e.commandId===2&&['ready','relocated'].includes(e.type)))
          } else {
            assert(!events.some(e=>e.type==='error'));assert(events.some(e=>e.type==='ready'))
            const position=events.find(e=>e.type==='relocated');assert(position)
            assert.equal(position.target.section,mode==='superseded'?0:1);assert.equal(position.target.blockId,mode==='superseded'?'0':'200')
            assert((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible)
            if(mode==='superseded')assert(!packets.some(e=>e.commandId===2&&['ready','relocated','error'].includes(e.type)))
          }
        }
        assert.deepEqual(errors,[]);results.push({mode,ok:true})
      } finally {state.held.forEach(res=>res.destroy());await page.close()}
    }
    console.log(JSON.stringify({ok:true,fixtures:docs.map(doc=>crypto.createHash('sha256').update(doc).digest('hex')),cases:results}))
  }finally{await browser.close();server.close()}
})().catch(error=>{console.error(error);server.close();process.exitCode=1})

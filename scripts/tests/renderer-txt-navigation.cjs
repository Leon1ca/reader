/* Public paginated host navigation: old real scroll debounce overlaps a target HTTP load. */
const {chromium}=require('playwright')
const fs=require('fs'),path=require('path'),http=require('http'),crypto=require('crypto'),assert=require('assert/strict')
const assets=path.resolve(__dirname,'../../app/src/main/assets/reader')
const allowed=['host.html','host.js','viewport.js','input.js','vendor/foliate/paginator.js']
const line='原创 navigation source 中文 English '.repeat(80)
const documents=[`<!doctype html><html><body>${Array.from({length:30},(_,i)=>`<p><span data-block="${i}" data-length="${line.length}">${line}</span></p>`).join('')}</body></html>`,
    '<!doctype html><html><body><p><span data-block="40" data-length="6">MIDDLE</span></p></body></html>',
    '<!doctype html><html><body><p><span data-block="49" data-length="11">TARGET LAST</span></p></body></html>']
const states=new Map()
const server=http.createServer((request,response)=>{
    const match=/^\/reader\/([^/]+)\/revision\/(.*)$/.exec(request.url)
    if(!match){response.writeHead(404);response.end();return}
    const state=states.get(match[1]),file=match[2],section=/^sections\/(\d+)\.html$/.exec(file)
    if(section){const index=Number(section[1]);if(index===2){state.held.push(response);state.requested();return}
        response.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});response.end(documents[index]);return}
    if(!allowed.includes(file)){response.writeHead(403);response.end();return}
    response.writeHead(200,{'Content-Type':file.endsWith('.js')?'text/javascript':'text/html'});response.end(fs.readFileSync(path.join(assets,file)))
})
;(async()=>{
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
    const browser=await chromium.launch({headless:true,executablePath:process.env.READER_CHROMIUM}),results=[]
    try {for(const mode of ['loaded','superseded','closed','missing','timeout']) {
        const state={held:[]};state.request=new Promise(resolve=>state.requested=resolve);states.set(mode,state)
        const page=await browser.newPage({viewport:{width:393,height:829}}),packets=[],errors=[]
        await page.exposeFunction('receive',raw=>packets.push(JSON.parse(raw)));await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.receive(raw)});page.on('pageerror',error=>errors.push(String(error)))
        const wire=(id,type,payload)=>({protocol:1,sessionId:mode,revision:'revision',commandId:id,layoutEpoch:id,type,payload})
        const send=(id,type,payload)=>page.evaluate(command=>readerHost.command(command),wire(id,type,payload))
        const terminal=id=>page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&['ready','error'].includes(e.type)),id,{timeout:15000})
        const release=()=>{for(const response of state.held){response.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});response.end(mode==='missing'?'<html><body><p>Real missing target</p></body></html>':documents[2])}state.held=[]}
        try {
            await page.goto(`http://127.0.0.1:${server.address().port}/reader/${mode}/revision/host.html`);await page.waitForFunction(()=>window.readerHost)
            // Public no-preload isolates the navigation owner from distant-view
            // eviction. The production host's ordinary preload regression remains separate.
            await send(1,'open',{sectionCount:3,profileId:'navigation',flow:'paginated',target:{section:0,blockId:'0',character:0},geometry:{gap:'7%','margin-top':'24px','margin-bottom':'24px','margin-left':'16px','margin-right':'16px','max-column-count':1,'no-preload':''},beforeAuthor:'html,body{margin:0}body{font:20px serif}p{margin:1em 0;line-height:1.6}',afterAuthor:''})
            await terminal(1);assert(packets.some(e=>e.commandId===1&&e.type==='ready'))
            await send(2,'goTo',{target:{section:0,blockId:'26',character:837}});await terminal(2)
            assert(packets.some(e=>e.commandId===2&&e.type==='ready'))
            await send(3,'goTo',{target:{section:2,blockId:'49',character:0}})
            let timer;try {await Promise.race([state.request,new Promise((_,reject)=>timer=setTimeout(()=>reject(Error('Controlled target request missing')),6000))])}finally{clearTimeout(timer)}
            // This is a finite network fixture delay, not a reader readiness sleep.
            // It crosses the original 250ms callback of the actual preceding scroll.
            await new Promise(resolve=>setTimeout(resolve,350))
            assert.equal(await page.evaluate(()=>document.getElementById('renderer').primaryIndex),2)
            if(mode==='superseded')await send(4,'goTo',{target:{section:0,blockId:'0',character:0}})
            if(mode==='closed') {
                await page.evaluate(()=>readerHost.close());const count=packets.length;release()
                await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))))
                assert.equal(packets.length,count);assert.equal(await page.evaluate(()=>document.getElementById('renderer').getContents().length),0)
            } else {
                if(mode!=='timeout')release()
                const latest=mode==='superseded'?4:3;await terminal(latest)
                const events=packets.filter(event=>event.commandId===latest)
                if(mode==='missing'||mode==='timeout') {
                    const error=events.find(event=>event.type==='error');assert(error)
                    assert.match(error.message,mode==='missing'?/Missing stable content block/:/timed out/)
                    const count=packets.length;release()
                    await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))))
                    assert(!packets.slice(count).some(event=>event.commandId===3&&['ready','relocated'].includes(event.type)))
                } else {
                    assert(!events.some(event=>event.type==='error'));assert(events.some(event=>event.type==='ready'))
                    const position=events.find(event=>event.type==='relocated');assert(position)
                    assert.equal(position.target.section,mode==='superseded'?0:2);assert.equal(position.target.blockId,mode==='superseded'?'0':'49')
                    assert((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible)
                    if(mode==='superseded')assert(!packets.some(event=>event.commandId===3&&['ready','relocated','error'].includes(event.type)))
                }
            }
            assert.deepEqual(errors,[]);results.push({mode,ok:true})
        } finally {state.held.forEach(response=>response.destroy());await page.close()}
    }
    console.log(JSON.stringify({ok:true,fixtureSha256:documents.map(value=>crypto.createHash('sha256').update(value).digest('hex')),cases:results}))
    }finally{await browser.close();server.close()}
})().catch(error=>{console.error(error);server.close();process.exitCode=1})

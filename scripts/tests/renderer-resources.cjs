/* Product host + pinned engine, delayed licensed font/local image and superseding-open regression. */
const {chromium}=require('playwright')
const http=require('http'),fs=require('fs'),path=require('path'),assert=require('assert/strict')
const assets=path.resolve(__dirname,'../../app/src/main/assets')
const base='/reader/resource-session/resource-v1/', identity={sessionId:'resource-session',revision:'resource-v1'}
let mode='slow',imageRequests=0,fontRequests=0
const sockets=new Set()
const contentCsp="default-src 'none'; script-src 'none'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self'; frame-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'"
const server=http.createServer((req,res)=>{
  if(!req.url.startsWith(base)){res.writeHead(403);res.end();return}
  const name=req.url.slice(base.length)
  const send=(mime,file,delay=0)=>setTimeout(()=>{if(!res.destroyed){res.writeHead(200,{'Content-Type':mime,'Cache-Control':'no-store'});res.end(fs.readFileSync(file))}},delay)
  if(['host.html','host.js','vendor/foliate/paginator.js'].includes(name))return send(name.endsWith('.js')?'text/javascript':'text/html',path.join(assets,'reader',name))
  if(name==='fonts/Roboto-Regular-VF.ttf'){fontRequests++;return send('font/ttf',path.join(assets,name),700)}
  if(name==='images/landscape.svg'){
    imageRequests++
    if(mode==='stalled')return // deliberately leave this controlled fixture request pending
    return send('image/svg+xml',path.join(assets,'reader/sample/landscape.svg'),800)
  }
  if(name==='sections/0.html'){
    const body='<h1><span data-block="title" data-length="7">Article</span></h1><p><span data-block="p" data-length="35">中文 English 123，font and image reflow。</span></p><figure><img data-block="image-1" data-element="image" data-length="0" src="../images/landscape.svg"><figcaption><span data-block="caption" data-length="13">Image caption</span></figcaption></figure><table><tr><td><span data-block="cell" data-length="7">Table 1</span></td></tr></table>'
    const delay=mode==='slow'?300:0
    return setTimeout(()=>{if(!res.destroyed){res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Content-Security-Policy':contentCsp});res.end(`<html><head><meta charset="utf-8"></head><body>${body}<script>parent.attacked=true</script><a href="javascript:parent.attacked=true" onclick="parent.attacked=true">unsafe</a><img src="https://external.invalid/evil" onerror="parent.attacked=true"></body></html>`.replace('<img src="https://external.invalid/evil" onerror="parent.attacked=true">',''))}},delay)
  }
  res.writeHead(403);res.end()
})
server.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket))})
;(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
 const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true})
 const page=await browser.newPage({viewport:{width:412,height:720}})
 const errors=[];page.on('pageerror',e=>errors.push(String(e)))
 const messages=[]
 await page.exposeFunction('received',raw=>messages.push(JSON.parse(raw)))
 await page.addInitScript(()=>{window.ReaderEvents={postMessage:raw=>window.received(raw)}})
 let id=0
 const rules={flow:'scrolled',profileId:'resource-fixture-v1',sectionCount:1,geometry:{gap:'7%','margin-top':'24px','margin-bottom':'24px','margin-left':'16px','margin-right':'16px','max-column-count':1},beforeAuthor:`@font-face{font-family:OwnedRoboto;src:url('../fonts/Roboto-Regular-VF.ttf')}body{font:20px OwnedRoboto;line-height:1.6}p{margin:1em 0}img{max-width:100%;height:auto}`,afterAuthor:''}
 function value(type,payload){return {protocol:1,...identity,commandId:++id,layoutEpoch:id,type,payload}}
 async function send(command){await page.evaluate(c=>readerHost.command(c),command)}
 async function settled(command){
   await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&['ready','error'].includes(e.type)),command.commandId,{timeout:16000})
   const error=messages.find(e=>e.commandId===command.commandId&&e.type==='error');assert.equal(error,undefined)
 }
 try{
   await page.goto(`http://127.0.0.1:${server.address().port}${base}host.html`);await page.waitForFunction(()=>window.readerHost)
   const first=value('open',{...rules,target:{section:0,blockId:'p',character:3}})
   await send(first);await page.waitForTimeout(80)
   const latest=value('open',{...rules,target:{section:0,blockId:'image-1',character:0}})
   const began=Date.now();await send(latest);await settled(latest)
   const snapshot=await page.evaluate(()=>readerHost.diagnostics())
   assert(snapshot.anchorGeometry.visible)
   assert(Date.now()-began>=650,'Ready must wait for deliberately slow font/image resources')
   assert(fontRequests>0&&imageRequests>0)
   assert.equal(await page.evaluate(()=>window.attacked),undefined)
   assert.equal(await page.evaluate(()=>document.getElementById('renderer').getContents()[0].doc.images[0].naturalWidth),480)
   const reflow=value('setRules',{...rules,beforeAuthor:rules.beforeAuthor.replace('20px','28px'),target:{section:0,blockId:'image-1',character:0}})
   await send(reflow);await settled(reflow)
   assert((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible)
   // Reload to avoid a cached successful image. A superseding close must unblock every wait.
   mode='stalled';await page.reload();await page.waitForFunction(()=>window.readerHost);id=0
   const stalled=value('open',{...rules,target:{section:0,blockId:'image-1',character:0}})
   await send(stalled);await page.waitForTimeout(250)
   await page.evaluate(()=>readerHost.close())
   const count=messages.length;await page.waitForTimeout(500);assert.equal(messages.length,count)
   // The same indefinite request must report bounded failure if the reader stays open.
   await page.reload();await page.waitForFunction(()=>window.readerHost);id=0
   const timeout=value('open',{...rules,target:{section:0,blockId:'image-1',character:0}})
   const start=Date.now();await send(timeout)
   await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&e.type==='error'),timeout.commandId,{timeout:15500})
   assert(Date.now()-start<14500)
   assert.equal(errors.length,0,errors.join('\n'))
   console.log(JSON.stringify({ok:true,delayedFont:true,delayedImage:true,imageElementVisible:true,supersedingOpen:true,closedWaitCancelled:true,timeoutBounded:true,fontRequests,imageRequests}))
 }finally{await browser.close();for(const socket of sockets)socket.destroy();server.close()}
})().catch(error=>{console.error(error);for(const socket of sockets)socket.destroy();server.close();process.exitCode=1})

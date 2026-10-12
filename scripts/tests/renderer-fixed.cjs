/* Production protocol-2 fixed-page host, trusted input, lease and cancellation regression. */
const {chromium}=require('playwright')
const http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict')
const assets=path.resolve(__dirname,'../../app/src/main/assets/reader'),base='/reader/fixed/revision/'
const errors=[],messages=[],held=[];let delaySection=-1,neverSection=-1
const server=http.createServer((req,res)=>{
  const resource=req.url.startsWith(base)?req.url.slice(base.length):'',match=/^sections\/(\d+)\.html$/.exec(resource)
  if(match){
    const i=Number(match[1]),text=`原创固定页 ${i} 中文 English 0123456789`,sha=crypto.createHash('sha256').update(text).digest('hex')
    if(i===neverSection){held.push(res);return}
    const send=()=>{res.writeHead(200,{'Content-Type':'text/html;charset=utf-8','Content-Security-Policy':"default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src data:; frame-src 'none'"});res.end(`<html><head><meta name="viewport" content="width=${600+i*2},height=${900+i*4}"><script>parent.compromised=true</script><style>html,body{margin:0;width:100%;height:100%;background:hsl(${i*17} 40% 85%)}p{position:absolute;left:20px;top:20px;font:28px serif}</style></head><body data-reader-canonical-length="${text.length}" data-reader-canonical-sha="${sha}"><p>${text}</p></body></html>`)}
    if(i===delaySection)setTimeout(send,600);else send();return
  }
  if(!['host3.html','host3.js','viewport.js','input.js','vendor/foliate/fixed-layout.js'].includes(resource)){res.writeHead(403);res.end();return}
  res.writeHead(200,{'Content-Type':resource.endsWith('.js')?'text/javascript':'text/html'});res.end(fs.readFileSync(path.join(assets,resource)))
})
;(async()=>{
  await new Promise(r=>server.listen(0,'127.0.0.1',r))
  const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true}),page=await browser.newPage({viewport:{width:400,height:600},hasTouch:true})
  page.on('pageerror',e=>errors.push(String(e)));page.on('console',m=>{if(m.type()==='error'&&!m.text().includes('Blocked script'))console.error(m.text())})
  await page.exposeFunction('receive',raw=>messages.push(JSON.parse(raw)));await page.addInitScript(()=>{window.ReaderEvents={postMessage:raw=>window.receive(raw)}})
  let id=0,epoch=0,peak=0
  const pages=Array.from({length:30},(_,index)=>({index,width:600+index*2,height:900+index*4,pageSpread:'',spread:'auto'})),target=(section,x=0,y=0)=>({kind:'PAGE',section,x,y})
  let rules={zoom:'fit-page',spread:'SINGLE',theme:'white',flow:'scrolled',paginationBase:'fixed-source-render-font-css-textZoom'}
  function wire(type,payload){return{protocol:2,sessionId:'fixed',revision:'revision',commandId:++id,layoutEpoch:['next','prev'].includes(type)?epoch:++epoch,intentToken:`cmd-${id}`,intentCause:['next','prev'].includes(type)?'UserNavigation':type==='setRules'?'Reflow':type==='goTo'?'ProgrammaticJump':'Restore',type,payload}}
  async function send(type,payload,wait=true){const cmd=wire(type,payload);console.error(`fixed command ${cmd.commandId}: ${type} ${payload.target?.section??''}`);await page.evaluate(c=>readerHost.command(c),cmd);if(wait)await ready(cmd.commandId);if(type==='open')await page.evaluate(()=>window.reusedEngine=document.getElementById('renderer'));else assert.equal(await page.evaluate(()=>window.reusedEngine===document.getElementById('renderer')),true,'goTo/setRules/page commands retain the fixed engine');return cmd.commandId}
  async function ready(commandId){await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&['ready','error'].includes(e.type)),commandId,{timeout:15000});const diag=await page.evaluate(()=>readerHost.diagnostics());assert.equal(diag.events.find(e=>e.commandId===commandId&&e.type==='error'),undefined,JSON.stringify(diag));assert(diag.anchorGeometry.visible,JSON.stringify(diag));assert(diag.activeViews<=4,JSON.stringify(diag));assert(diag.contents.every(c=>c.validated),JSON.stringify(diag));peak=Math.max(peak,diag.allocatedFrames);console.error(`fixed ready ${commandId}`);return diag}
  try{
    await page.goto(`http://127.0.0.1:${server.address().port}${base}host3.html`);await page.waitForFunction(()=>window.readerHost)
    await send('open',{...rules,target:target(17),fixedPages:pages,sectionCount:30,direction:'ltr'})
    for(let i=0;i<30;i++)await send('goTo',{target:target(i)})
    // Resize after the point first becomes visible but before Ready: late RO
    // must not recenter a pending two-axis source anchor.
    const resizeId=await send('setRules',{...rules,flow:'paginated',zoom:'2',target:target(12,.25,.5)},false)
    await page.waitForFunction(id=>readerHost.diagnostics().commandId===id&&readerHost.diagnostics().anchorGeometry.visible&&!readerHost.diagnostics().events.some(e=>e.commandId===id&&e.type==='ready'),resizeId)
    await page.setViewportSize({width:440,height:640})
    const resized=await ready(resizeId)
    assert(Math.abs(resized.target.x-.25)<.001&&Math.abs(resized.target.y-.5)<.001,JSON.stringify(resized))
    await page.setViewportSize({width:400,height:600})
    await send('setRules',{...rules,zoom:'2',target:target(12,.2,.35)})
    let diag=await page.evaluate(()=>readerHost.diagnostics());assert.equal(diag.target.section,12);assert.equal(diag.anchorGeometry.width,1248);assert(Math.abs(diag.target.y-.35)<.02,JSON.stringify(diag))
    await page.setViewportSize({width:500,height:700});await send('setRules',{...rules,zoom:'2',target:target(12,.2,.35)})
    await send('setRules',{...rules,flow:'paginated',zoom:'fit-page',target:target(8)})
    await send('next',{});diag=await page.evaluate(()=>readerHost.diagnostics());assert.equal(diag.target.section,9,JSON.stringify(diag));await send('prev',{})
    await send('setRules',{...rules,flow:'paginated',zoom:'2',target:target(8,.2,.35)})
    const beforePan=await page.evaluate(()=>readerHost.diagnostics().target)
    const cdp=await page.context().newCDPSession(page)
    await cdp.send('Input.dispatchMouseEvent',{type:'mouseMoved',x:250,y:300,button:'none'})
    await cdp.send('Input.dispatchMouseEvent',{type:'mousePressed',x:250,y:300,button:'left',buttons:1,clickCount:1})
    await cdp.send('Input.dispatchMouseEvent',{type:'mouseMoved',x:210,y:250,button:'left',buttons:1})
    await cdp.send('Input.dispatchMouseEvent',{type:'mouseReleased',x:210,y:250,button:'left',buttons:0,clickCount:1})
    await page.waitForTimeout(350)
    const afterPan=await page.evaluate(()=>readerHost.diagnostics().target);assert(afterPan.x>beforePan.x||afterPan.y>beforePan.y,'trusted drag really pans the page');assert(messages.some(e=>e.type==='userIntent'),'trusted pan input is recorded')
    await send('open',{...rules,flow:'paginated',target:target(8),fixedPages:pages,sectionCount:30,direction:'rtl'})
    await send('next',{});diag=await page.evaluate(()=>readerHost.diagnostics());assert.equal(diag.target.section,9,JSON.stringify(diag))
    // A trusted double tap changes actual page scale and requests a persisted setting.
    for(let tap=0;tap<2;tap++) {
      await cdp.send('Input.dispatchMouseEvent',{type:'mousePressed',x:250,y:300,button:'left',buttons:1,clickCount:1})
      await cdp.send('Input.dispatchMouseEvent',{type:'mouseReleased',x:250,y:300,button:'left',buttons:0,clickCount:1})
    }
    await page.waitForTimeout(250)
    assert(messages.some(e=>e.type==='zoomRequested'&&e.zoom===2&&e.userIntentToken),'double tap requests a trusted persisted zoom')
    // Two genuine touch pointers pass through the inert iframe to the trusted host.
    await send('setRules',{...rules,flow:'paginated',zoom:'1',target:target(9)})
    const zoomEventsBefore=messages.filter(e=>e.type==='zoomRequested').length
    const touch=(type,points)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:points.map(([id,x,y])=>({id,x,y,radiusX:2,radiusY:2}))})
    await touch('touchStart',[[1,180,300],[2,220,300]])
    await touch('touchMove',[[1,150,300],[2,250,300]])
    await touch('touchEnd',[])
    await page.waitForTimeout(250)
    const pinch=messages.filter(e=>e.type==='zoomRequested').slice(zoomEventsBefore).at(-1)
    assert(pinch&&pinch.zoom>1&&pinch.zoom<=5&&pinch.userIntentToken,'real two-pointer pinch requests persisted bounded zoom')
    assert((await page.evaluate(()=>readerHost.diagnostics().anchorGeometry.width))>600,'pinch changes rendered page dimensions')
    await send('setRules',{...rules,flow:'paginated',target:target(9)})
    const pageEventsBefore=messages.filter(e=>e.type==='pageRequested').length
    await touch('touchStart',[[1,160,300]])
    await touch('touchMove',[[1,250,300]])
    await touch('touchEnd',[])
    await page.waitForTimeout(150)
    const swipe=messages.filter(e=>e.type==='pageRequested').slice(pageEventsBefore).at(-1)
    assert(swipe&&swipe.direction==='next'&&swipe.userIntentToken,'real fitted RTL swipe enters native page queue')
    await send('next',{})
    await send('goTo',{target:target(29)})
    assert((await page.evaluate(()=>readerHost.diagnostics())).atEnd,'last fitted page is a verified end boundary')
    let viewport=messages.filter(e=>e.commandId===id&&e.type==='relocated').at(-1).viewport
    assert(viewport.atEnd&&viewport.visiblePages.includes(29));assert(viewport.samples.every(s=>s.pages===1&&!s.estimated))
    await send('setRules',{...rules,flow:'paginated',zoom:'2',target:target(29,.25,.5)})
    viewport=messages.filter(e=>e.commandId===id&&e.type==='relocated').at(-1).viewport
    assert(viewport.atEnd&&viewport.visiblePages.includes(29),'zoomed partial last page remains the geometric end')
    await page.setViewportSize({width:1000,height:600})
    await send('open',{...rules,flow:'paginated',spread:'AUTO',target:target(2),fixedPages:pages.map(p=>({...p,spread:'none'})),sectionCount:30,direction:'ltr'})
    diag=await page.evaluate(()=>readerHost.diagnostics());assert.deepEqual(diag.visiblePages,[2],'AUTO respects author spread-none')
    await send('open',{...rules,flow:'paginated',spread:'AUTO',target:{...target(2),nearby:true},fixedPages:pages,sectionCount:30,direction:'ltr'})
    diag=await page.evaluate(()=>readerHost.diagnostics());assert(diag.visiblePages.length===2,JSON.stringify(diag));assert.equal(diag.target.section,Math.min(...diag.visiblePages));assert(diag.events.some(e=>e.commandId===id&&e.type==='ready'&&e.nearby))
    viewport=messages.filter(e=>e.commandId===id&&e.type==='relocated').at(-1).viewport
    assert.deepEqual(viewport.visiblePages,diag.visiblePages);assert.equal(viewport.currentSection,Math.min(...diag.visiblePages))
    // An author single-page left/right spread has a decorative blank partner.
    // Only that explicit blank is omitted from content readiness; it still has a lease.
    for(const side of ['left','right']) {
      delaySection=0
      const single=await send('open',{...rules,flow:'paginated',spread:'AUTO',target:target(0),fixedPages:[{...pages[0],pageSpread:side}],sectionCount:1,direction:'ltr'},false)
      await page.waitForTimeout(180)
      const loading=await page.evaluate(()=>({d:readerHost.diagnostics(),contents:document.querySelector('foliate-fxl').getContents().map(c=>({index:c.index,marked:Boolean(c.doc?.body?.hasAttribute('data-reader-canonical-sha'))}))}))
      assert(!loading.d.events.some(e=>e.commandId===single&&e.type==='ready'),'a real loading source is not discarded as blank')
      assert(loading.d.frameAdmissions.some(c=>['pending','verifying'].includes(c.state)),JSON.stringify(loading));assert(loading.contents.every(c=>c.marked),'only admitted documents are exposed as loaded content')
      diag=await ready(single)
      assert.deepEqual(diag.visiblePages,[0]);assert.equal(diag.contents.length,1);assert.equal(diag.allocatedFrames,2,'blank partner still counts against the frame lease budget')
      delaySection=-1
    }
    await page.setViewportSize({width:400,height:600})
    await send('open',{...rules,flow:'paginated',target:target(0),fixedPages:pages,sectionCount:30,direction:'ltr'})
    delaySection=5;await send('goTo',{target:target(5)},false);await page.waitForTimeout(60);const latest=await send('goTo',{target:target(8)});diag=await ready(latest);assert.equal(diag.target.section,8,JSON.stringify(diag))
    await page.waitForTimeout(750);diag=await page.evaluate(()=>readerHost.diagnostics());assert.equal(diag.target.section,8,JSON.stringify(diag));assert.equal(diag.contents[0].section,8)
    neverSection=21;await send('goTo',{target:target(21)},false);await page.waitForTimeout(80);const recover=await send('goTo',{target:target(3)});await ready(recover)
    assert.equal(await page.evaluate(()=>window.compromised),undefined);assert.deepEqual(errors,[])
    neverSection=22;await send('goTo',{target:target(22)},false);await page.waitForTimeout(80);await page.evaluate(()=>readerHost.close());await page.waitForTimeout(100);assert.equal(await page.evaluate(()=>document.querySelectorAll('foliate-fxl').length),0)
    console.log(JSON.stringify({ok:true,firstVisibleTwoAxis:true,thirtyPages:true,resize:true,loadingResize:true,rtl:true,trustedPan:true,trustedPinch:true,trustedSwipe:true,endBoundary:true,autoSingleBlank:true,loadingSourcePreserved:true,latestNavigation:true,neverLoadClose:true,peak,pageErrors:errors}))
  }finally{held.forEach(x=>x.destroy());await browser.close();server.close()}
})().catch(e=>{console.error(e);server.close();process.exitCode=1})

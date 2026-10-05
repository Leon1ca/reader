/* Controlled WebView quantization regression. The measured phone readback was
 * 346.153839 <-> 345.846161 CSS px at DPR 3.25, with one float32 ULP of
 * asymmetric frame geometry. This models that native behavior in real DOM;
 * it does not claim desktop Chromium naturally reproduces the phone bug. */
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
  if(!['host3.html','host3.js','viewport.js','vendor/foliate/fixed-layout.js'].includes(resource)){res.writeHead(403);res.end();return}
  res.writeHead(200,{'Content-Type':resource.endsWith('.js')?'text/javascript':'text/html'});res.end(fs.readFileSync(path.join(assets,resource)))
})
;(async()=>{
  await new Promise(r=>server.listen(0,'127.0.0.1',r))
  const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true}),page=await browser.newPage({viewport:{width:400,height:600},hasTouch:true})
  page.on('pageerror',e=>errors.push(String(e)));page.on('console',m=>{if(m.type()==='error'&&!m.text().includes('Blocked script'))console.error(m.text())})
  await page.exposeFunction('receive',raw=>messages.push(JSON.parse(raw)));await page.addInitScript(()=>{window.ReaderEvents={postMessage:raw=>window.receive(raw)}})
  let id=0,epoch=0,peak=0
  const pages=Array.from({length:30},(_,index)=>({index,width:600+index*2,height:900+index*4,pageSpread:'',spread:'auto'})),target=(section,x=0,y=0)=>({kind:'PAGE',section,x,y})
  let rules={zoom:'fit-page',spread:'SINGLE',theme:'white',flow:'scrolled'}
  function wire(type,payload){return{protocol:2,sessionId:'fixed',revision:'revision',commandId:++id,layoutEpoch:['next','prev'].includes(type)?epoch:++epoch,intentToken:`cmd-${id}`,intentCause:['next','prev'].includes(type)?'UserNavigation':type==='setRules'?'Reflow':type==='goTo'?'ProgrammaticJump':'Restore',type,payload}}
  async function send(type,payload,wait=true){const cmd=wire(type,payload);console.error(`fixed command ${cmd.commandId}: ${type} ${payload.target?.section??''}`);await page.evaluate(c=>readerHost.command(c),cmd);if(wait)await ready(cmd.commandId);return cmd.commandId}
  async function ready(commandId){await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&['ready','error'].includes(e.type)),commandId,{timeout:15000});const diag=await page.evaluate(()=>readerHost.diagnostics());assert.equal(diag.events.find(e=>e.commandId===commandId&&e.type==='error'),undefined,JSON.stringify(diag));assert(diag.anchorGeometry.visible,JSON.stringify(diag));assert(diag.activeViews<=4,JSON.stringify(diag));assert(diag.contents.every(c=>c.validated),JSON.stringify(diag));peak=Math.max(peak,diag.allocatedFrames);console.error(`fixed ready ${commandId}`);return diag}
  try{
    await page.goto(`http://127.0.0.1:${server.address().port}${base}host3.html`);await page.waitForFunction(()=>window.readerHost)
    await send('open',{...rules,flow:'paginated',target:target(17),fixedPages:pages,sectionCount:30,direction:'ltr'})
    await page.evaluate(()=>{
      Object.defineProperty(window,'devicePixelRatio',{value:3.25,configurable:true})
      const proto=Object.getPrototypeOf(document.querySelector('foliate-fxl')),native=Object.getOwnPropertyDescriptor(Element.prototype,'scrollLeft'),values=new WeakMap()
      window.quantizationWrites=[]
      Object.defineProperty(proto,'scrollLeft',{configurable:true,get(){return values.get(this)??native.get.call(this)},set(value){
        if(this.getAttribute('flow')!=='scrolled'||this.getAttribute('zoom')!=='2'){native.set.call(this,value);return}
        const q=Math.fround(Math.round(value*3.25)/3.25)
        values.set(this,q);native.set.call(this,q)
        const container=this.shadowRoot.querySelector('.scroll-container')
        if(container)container.style.translate=`${native.get.call(this)-q-(q>value?2**-15:0)}px 0`
        if(window.quantizationWrites.length===12)window.quantizationWrites.shift()
        window.quantizationWrites.push({requested:value,actual:q,native:native.get.call(this)})
      }})
    })
    const command=await send('setRules',{...rules,zoom:'2',target:target(12,.25,.5)},false)
    await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&['ready','error'].includes(e.type)),command,{timeout:15000})
    const d=await page.evaluate(()=>({renderer:readerHost.diagnostics(),writes:quantizationWrites}))
    assert(!d.renderer.events.some(e=>e.commandId===command&&e.type==='error'),JSON.stringify(d))
    assert(d.renderer.events.some(e=>e.commandId===command&&e.type==='ready'),JSON.stringify(d))
    assert.equal(d.renderer.target.section,12)
    assert(d.renderer.anchorGeometry.visible,JSON.stringify(d))
    assert(d.renderer.stabilitySamples.at(-1).count>=3,JSON.stringify(d))
    assert(d.renderer.stabilitySamples.every(s=>s.point.visible),JSON.stringify(d))
    assert(d.writes.length<=2,'already nearest physical pixel must not be repeatedly rewritten')
    assert(Math.abs(d.renderer.stabilitySamples.at(-1).point.left)<=.501/3.25,JSON.stringify(d))
    assert(d.renderer.allocatedFrames<=4);assert.deepEqual(errors,[])
    console.log(JSON.stringify({ok:true,measuredNativeQuantizationModel:true,stableCount:d.renderer.stabilitySamples.at(-1).count,writes:d.writes,samples:d.renderer.stabilitySamples,pageErrors:errors}))
  }finally{held.forEach(x=>x.destroy());await browser.close();server.close()}
})().catch(e=>{console.error(e);server.close();process.exitCode=1})

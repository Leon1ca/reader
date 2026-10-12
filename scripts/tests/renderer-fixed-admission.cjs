/* Actual SHA admission, delayed neighbouring resources and canceled document leases. */
const {chromium}=require('playwright'),http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict')
const assets=path.resolve(__dirname,'../../app/src/main/assets/reader'),base='/reader/admission/revision/'
let bad=-1;const records=[]
const server=http.createServer((req,res)=>{
 const name=req.url.slice(base.length),match=/^sections\/(\d+)\.html$/.exec(name)
 if(match){const index=Number(match[1]),text=`Owned fixed admission ${index}`,sha=crypto.createHash('sha256').update(text).digest('hex');const send=()=>{res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});res.end(`<html><head><meta name="viewport" content="width=600,height=900"><style>body{margin:0;background:white}p{font:24px serif}</style></head><body data-reader-canonical-length="${text.length}" data-reader-canonical-sha="${index===bad?'0'.repeat(64):sha}"><p>${text}</p></body></html>`)};if(index===1)setTimeout(send,180);else send();return}
 if(!['host3.html','host3.js','viewport.js','input.js','vendor/foliate/fixed-layout.js'].includes(name)){res.writeHead(404);res.end();return}
 res.writeHead(200,{'Content-Type':name.endsWith('.js')?'text/javascript':'text/html'});res.end(fs.readFileSync(path.join(assets,name)))
})
;(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true})
 try{for(const flow of ['paginated','scrolled'])for(const action of ['release','badSha','close','rebuild']){
  bad=action==='badSha'?1:-1
  const page=await browser.newPage({viewport:{width:400,height:600}}),messages=[],errors=[]
  page.on('pageerror',e=>errors.push(String(e)))
  await page.exposeFunction('receive',s=>messages.push(JSON.parse(s)))
  await page.addInitScript(()=>{
   window.ReaderEvents={postMessage:s=>window.receive(s)}
   const original=crypto.subtle.digest.bind(crypto.subtle),gates=[]
   window.admissionControl={held:1,entries:[],release(){this.held=-1;gates.splice(0).forEach(r=>r())}}
   crypto.subtle.digest=async function(algorithm,data){const text=new TextDecoder().decode(data),result=await original(algorithm,data);if(text===`Owned fixed admission ${window.admissionControl.held}`){window.admissionControl.entries.push(text);await new Promise(r=>gates.push(r))}return result}
  })
  await page.goto(`http://127.0.0.1:${server.address().port}${base}host3.html`);await page.waitForFunction(()=>window.readerHost)
  let command=0;const send=async(section,type='open')=>{const commandId=++command;await page.evaluate(c=>readerHost.command(c),{protocol:2,sessionId:'admission',revision:'revision',commandId,layoutEpoch:commandId,intentToken:`c${commandId}`,intentCause:'Restore',type,payload:{zoom:'fit-page',spread:'SINGLE',theme:'white',flow,direction:'ltr',sectionCount:3,fixedPages:Array.from({length:3},(_,index)=>({index,width:600,height:900,spread:'none'})),target:{kind:'PAGE',section,x:0,y:0}}});return commandId}
  const ready=async id=>{await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&['ready','error'].includes(e.type)),id,{timeout:15000});const d=await page.evaluate(()=>readerHost.diagnostics());assert(!d.events.some(e=>e.commandId===id&&e.type==='error'),JSON.stringify(d));assert(d.contents.every(c=>c.validated),JSON.stringify(d));assert(d.allocatedFrames<=4,JSON.stringify(d));assert(d.anchorGeometry.visible);return d}
  // Paginated loads the targeted page; scrolled loads the true delayed neighbour after page 0.
  const first=await send(flow==='paginated'?1:0)
  await page.waitForFunction(()=>admissionControl.entries.length===1,{timeout:10000})
  const pending=await page.evaluate(()=>readerHost.diagnostics())
  assert(pending.frameAdmissions.some(f=>f.index===1&&f.state==='verifying'),JSON.stringify(pending))
  assert(!pending.contents.some(c=>c.section===1),'unverified SHA is not a published content document')
  assert(pending.allocatedFrames>0&&pending.allocatedFrames<=4)
  if(flow==='scrolled')await ready(first)
  const eventCount=messages.length
  if(action==='close'){
   await page.evaluate(()=>readerHost.close());await page.evaluate(()=>admissionControl.release());await page.waitForTimeout(250)
   assert.equal(await page.evaluate(()=>document.querySelectorAll('foliate-fxl').length),0)
   assert(!messages.slice(eventCount).some(e=>['ready','relocated','sectionLoaded'].includes(e.type)))
  }else if(action==='rebuild'){
   const latest=await send(2);await ready(latest);await page.evaluate(()=>admissionControl.release());await page.waitForTimeout(250)
   const d=await page.evaluate(()=>readerHost.diagnostics());assert.equal(d.commandId,latest);assert.equal(d.target.section,2);assert(d.contents.every(c=>c.validated));assert(!messages.slice(eventCount).some(e=>e.commandId===first&&e.type==='ready'))
  }else{
   await page.evaluate(()=>admissionControl.release())
   if(action==='badSha'){
    await page.waitForFunction(()=>readerHost.diagnostics().events.some(e=>e.type==='error'&&e.message.includes('canonical hash mismatch')),{timeout:10000})
    const d=await page.evaluate(()=>readerHost.diagnostics());assert(!d.contents.some(c=>c.section===1));assert(!d.frameAdmissions.some(f=>f.index===1&&f.state==='loaded'));assert(d.allocatedFrames<=4)
   }else{
    if(flow==='paginated')await ready(first)
    await page.waitForFunction(()=>readerHost.diagnostics().contents.some(c=>c.section===1&&c.validated),{timeout:10000})
    const d=await page.evaluate(()=>readerHost.diagnostics());assert(d.contents.every(c=>c.validated));assert(d.allocatedFrames<=4);assert(!d.events.some(e=>e.type==='error'))
   }
  }
  assert.deepEqual(errors,[]);records.push({flow,action,heldState:pending.frameAdmissions,allocatedFrames:pending.allocatedFrames,ok:true});await page.close()
 }}finally{await browser.close();server.close()}
 console.log(JSON.stringify({ok:true,cases:records}))
})().catch(e=>{console.error(e);server.close();process.exitCode=1})

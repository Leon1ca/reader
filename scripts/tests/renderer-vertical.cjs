/* Actual MIT paginator/host: authored vertical roots, margins, source anchors and both flows. */
const {chromium}=require('playwright'),http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict')
const assets=path.resolve(__dirname,'../../app/src/main/assets/reader'),base='/reader/vertical/revision/'
const text='ACTUAL TRUSTED INPUT 中文😀 '.repeat(300),sha=crypto.createHash('sha256').update(text).digest('hex')
let writing='vertical-rl',declaration='html',mixed=false,multiple=false
const server=http.createServer((req,res)=>{
 const name=req.url.slice(base.length)
 if(/^sections\/[01]\.html$/.test(name)){res.writeHead(200,{'Content-Type':'text/html','Content-Security-Policy':"default-src 'none';script-src 'none';style-src 'unsafe-inline'"});return res.end(`<html><head><style>${declaration}{writing-mode:${multiple&&name==='sections/1.html'?'vertical-lr':writing}}</style></head><body data-reader-canonical-length="${text.length}" data-reader-canonical-sha="${sha}"><p style="margin:0"><span data-block="0" data-length="${text.length}">${mixed?text.slice(0,3900):text}</span></p>${mixed?`<p style="writing-mode:horizontal-tb;margin:0">${text.slice(3900)}</p>`:""}</body></html>`)}
 const file=path.resolve(assets,name);if(!file.startsWith(assets+'/')||!fs.existsSync(file)){res.writeHead(404);return res.end()}
 res.writeHead(200,{'Content-Type':name.endsWith('.js')?'text/javascript':'text/html'});res.end(fs.readFileSync(file))
})
;(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true}),results=[]
 try{
 for(const protocol of [1,2])for(const mode of ['vertical-rl','vertical-lr'])for(const root of ['html','body','p'])for(const ppd of ['default','ltr','rtl']) {
  writing=mode;declaration=root;console.log('BEGIN',protocol,mode,root,ppd);const page=await browser.newPage({viewport:{width:851,height:883}}),messages=[],errors=[]
  page.on('pageerror',e=>errors.push(String(e)));await page.exposeFunction('receive',raw=>messages.push(JSON.parse(raw)));await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.receive(raw)})
  let id=0,epoch=0
  const target=offset=>protocol===1?{section:0,blockId:'0',character:offset}:{section:0,kind:'TEXT',offset}
  const rules={sectionCount:1,direction:ppd,flow:'paginated',paginationBase:'vertical-authored',profileId:'vertical',nativeInsets:{left:0,top:64,right:0,bottom:184,width:851},geometry:{gap:'7%','margin-top':'24px','margin-bottom':'24px','margin-left':'16px','margin-right':'16px','max-column-count':1},beforeAuthor:'html,body{margin:0;padding:0}body{font:20px serif;line-height:1.5}',afterAuthor:''}
  async function command(type,payload){const next={protocol,sessionId:'vertical',revision:'revision',commandId:++id,layoutEpoch:protocol===1||!['next','prev'].includes(type)?++epoch:epoch,intentToken:`cmd${id}`,intentCause:['next','prev'].includes(type)?'UserNavigation':type==='setRules'?'Reflow':'Restore',type,payload};await page.evaluate(c=>readerHost.command(c),next);await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&['relocated','error'].includes(e.type)),id,{timeout:15000});const failure=messages.find(e=>e.commandId===id&&e.type==='error');assert.equal(failure,undefined,`${protocol}/${mode}/${root}: ${JSON.stringify(failure)}`);return messages.filter(e=>e.commandId===id&&e.type==='relocated').at(-1)}
  try{
   await page.goto(`http://127.0.0.1:${server.address().port}${base}${protocol===1?'host':'host2'}.html`);await page.waitForFunction(()=>readerHost)
   const first=await command('open',{...rules,target:target(0)})
   const geometry=await page.evaluate(()=>{const r=document.getElementById('renderer'),doc=r.getContents()[0].doc;return {columns:r.columnCount,columnWidth:doc.defaultView.getComputedStyle(doc.documentElement).columnWidth,writing:doc.defaultView.getComputedStyle(doc.querySelector('p')).writingMode}})
   assert.equal(geometry.writing,mode);assert.equal(geometry.columns,1);assert(parseFloat(geometry.columnWidth)>0)
   const next=await command('next',{}),offset=e=>e.target.offset??e.target.character
   assert(offset(next)>offset(first),'next advances actual logical source');const back=await command('prev',{});console.log('POSITIONS',offset(first),offset(next),offset(back));assert.equal(offset(back),offset(first))
   let e=back,steps=0,previous=offset(back);while(!e.viewport.atEnd && steps<40){e=await command('next',{});const actual=offset(e);assert(actual>previous || e.viewport.atEnd,'each actual page advances the source');previous=actual;steps++}
   assert(e.viewport.atEnd&&steps<40,'complete traversal reaches real final-page geometry');
   for(const position of [3900,7797]){await command('goTo',{target:target(position)});assert.equal((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible,true)}
   await command('setRules',{...rules,flow:'scrolled',target:target(3900)});assert.equal((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible,true)
   const scrollNext=await command('next',{});assert(offset(scrollNext)>3900,'scrolled next advances actual source')
   await command('setRules',{...rules,target:target(3900)});assert.equal((await page.evaluate(()=>readerHost.diagnostics())).anchorGeometry.visible,true)
   assert.deepEqual(errors,[]);results.push({protocol,mode,root,ppd,geometry,nextSource:offset(next)})
  }finally{await page.close()}
 }
 console.log(JSON.stringify({ok:true,canonicalSHA:sha,cases:results}))
 }finally{await browser.close();server.close()}
})().catch(e=>{console.error(e);server.close();process.exitCode=1})

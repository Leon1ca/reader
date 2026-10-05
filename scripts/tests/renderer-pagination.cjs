/* Production EPUB host: measured part ownership, real source intervals and geometric end. */
const {chromium}=require('playwright'),http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict')
const assets=path.resolve(__dirname,'../../app/src/main/assets/reader'),base='/reader/pages/revision/',messages=[],errors=[]
const paragraphs=Array.from({length:3},(_,i)=>Array.from({length:[3,80,24][i]},(_,n)=>`章节${i}段${n} English😀 `+'原创正文 '.repeat(20)))
const server=http.createServer((req,res)=>{
 const file=req.url.startsWith(base)?req.url.slice(base.length):'',match=/^sections\/(\d+)\.html$/.exec(file)
 if(match){const i=Number(match[1]),text=paragraphs[i].join(''),sha=crypto.createHash('sha256').update(text).digest('hex');res.writeHead(200,{'Content-Type':'text/html','Content-Security-Policy':"default-src 'none'; script-src 'none'; style-src 'unsafe-inline'"});res.end(`<html><body data-reader-canonical-length="${text.length}" data-reader-canonical-sha="${sha}">${paragraphs[i].map(x=>`<p>${x}</p>`).join('')}</body></html>`);return}
 if(!['host2.html','host2.js','viewport.js','vendor/foliate/paginator.js'].includes(file)){res.writeHead(403);res.end();return}
 res.writeHead(200,{'Content-Type':file.endsWith('.js')?'text/javascript':'text/html'});res.end(fs.readFileSync(path.join(assets,file)))
})
;(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true}),page=await browser.newPage({viewport:{width:900,height:600}})
 page.on('pageerror',e=>errors.push(String(e)));await page.exposeFunction('receive',raw=>messages.push(JSON.parse(raw)));await page.addInitScript(()=>window.ReaderEvents={postMessage:raw=>window.receive(raw)})
 let id=0,epoch=0
 const rules={flow:'paginated',paginationBase:'source-render-font-css-textZoom',profileId:'test',geometry:{gap:'7%','margin-top':'24px','margin-bottom':'24px','margin-left':'16px','margin-right':'16px','max-column-count':2},beforeAuthor:'html,body{margin:0}body{font:20px serif}p{margin:1em 0;line-height:1.6}',afterAuthor:''}
 async function command(type,payload){const cmd={protocol:2,sessionId:'pages',revision:'revision',commandId:++id,layoutEpoch:['next','prev'].includes(type)?epoch:++epoch,intentToken:`cmd${id}`,intentCause:['next','prev'].includes(type)?'UserNavigation':type==='setRules'?'Reflow':'Restore',type,payload};await page.evaluate(c=>readerHost.command(c),cmd);await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&['ready','error'].includes(e.type)),id,{timeout:15000});assert.equal(messages.find(e=>e.commandId===id&&e.type==='error'),undefined);await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&e.type==='relocated'),id);return messages.filter(e=>e.commandId===id&&e.type==='relocated').at(-1)}
 try{
  await page.goto(`http://127.0.0.1:${server.address().port}${base}host2.html`);await page.waitForFunction(()=>readerHost)
  const initial=await command('open',{...rules,sectionCount:3,target:{section:0,kind:'TEXT',offset:0}})
  const signature=initial.viewport.signature;assert(!initial.viewport.atEnd);assert(initial.viewport.ranges.length>0)
  const checked=[]
  for(let n=0;n<14;n++) {
   const event=n?await command('next',{}):initial,v=event.viewport
   assert.equal(v.base,rules.paginationBase);assert.equal(v.signature,signature,'same geometry identity survives a different visible-part set');assert(v.ranges.length>0)
   const actual=await page.evaluate(()=>{const r=document.getElementById('renderer');return r.getContents().filter(x=>x.doc.body&&x.doc.readyState==='complete').map(x=>({section:x.index,pages:Math.max(1,Math.round(x.doc.defaultView.frameElement.getBoundingClientRect()[r.sideProp]/(r.size/r.columnCount)))}))})
   for(const sample of v.samples){assert.equal(sample.pages,actual.find(x=>x.section===sample.section).pages);assert(!sample.estimated);assert(sample.page>=1&&sample.page<=sample.pages)}
   assert(v.ranges.some(x=>x.start.section===event.target.section&&x.start.offset<=event.target.offset&&x.end.offset>event.target.offset),'actual first glyph belongs to a measured source range');const glyphs=await page.evaluate(ranges=>{const engine=document.getElementById('renderer'),view=engine.getBoundingClientRect();return ranges.map(span=>{const doc=engine.getContents().find(x=>x.index===span.start.section).doc,walker=doc.createTreeWalker(doc.body,NodeFilter.SHOW_TEXT);let node,left=span.start.offset;while((node=walker.nextNode())&&left>=node.length)left-=node.length;if(!node)return false;const range=doc.createRange();range.setStart(node,left);range.setEnd(node,left+(node.data.codePointAt(left)>0xffff?2:1));const b=range.getBoundingClientRect(),f=doc.defaultView.frameElement.getBoundingClientRect();return b.width>.1&&b.height>.1&&b.right+f.left>view.left&&b.left+f.left<view.right&&b.bottom+f.top>view.top&&b.top+f.top<view.bottom})},v.ranges)
   assert(glyphs.every(Boolean),'each interval begins at a real visible source glyph');checked.push(v.currentSection)
  }
  const end=await command('goTo',{target:{section:2,kind:'TEXT',offset:paragraphs[2].join('').length}})
  assert(end.viewport.atEnd,'last source glyph is on the final actual page even though its source fraction is below 1');assert(end.target.offset<paragraphs[2].join('').length)
  const back=await command('prev',{});assert(!back.viewport.atEnd)
  const scroll=await command('setRules',{...rules,flow:'scrolled',target:{section:1,kind:'TEXT',offset:100}})
  assert(scroll.viewport.samples.every(x=>x.estimated&&x.scrolled&&x.units>0));assert(!scroll.viewport.atEnd)
  assert.deepEqual(errors,[]);console.log(JSON.stringify({ok:true,checked,currentSections:[...new Set(checked)],endFractionBelowOne:true,scrollEstimated:true,pageErrors:errors}))
 }finally{await browser.close();server.close()}
})().catch(error=>{console.error(error);server.close();process.exitCode=1})

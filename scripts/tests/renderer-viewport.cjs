/* Source geometry must include the inline-axis endpoints in horizontal and vertical books. */
const {chromium}=require('playwright'),http=require('http'),fs=require('fs'),path=require('path'),assert=require('assert/strict')
const modulePath=path.resolve(__dirname,'../../app/src/main/assets/reader/viewport.js')
const server=http.createServer((req,res)=>{res.writeHead(200,{'Content-Type':req.url==='/viewport.js'?'text/javascript':'text/html'});res.end(req.url==='/viewport.js'?fs.readFileSync(modulePath):'<html><body style="margin:0"><iframe style="border:0;width:300px;height:300px"></iframe></body></html>')})
;(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true}),page=await browser.newPage({viewport:{width:400,height:400}})
 const errors=[];page.on('pageerror',e=>errors.push(String(e)))
 try {
  await page.goto(`http://127.0.0.1:${server.address().port}`)
  const results=await page.evaluate(async()=>{
   const {textIntervals,visibleCaret,fontGeometry}=await import('/viewport.js'),iframe=document.querySelector('iframe'),doc=iframe.contentDocument,viewport={left:0,top:0,right:300,bottom:300}
   const results=[]
   for(const writingMode of ['horizontal-tb','vertical-rl','vertical-lr'])for(const direction of ['ltr','rtl']) {
    doc.body.innerHTML='<p id="text">ABCDEFGHIJKLMNO😀尾</p><p style="display:none">HIDDEN</p>';doc.body.style.cssText=`margin:0;font:16px monospace;writing-mode:${writingMode};direction:${direction}`
    const node=doc.getElementById('text').firstChild,ranges=textIntervals(doc,viewport).filter(x=>x.node===node)
    const first=Math.min(...ranges.map(x=>x.start)),last=Math.max(...ranges.map(x=>x.end));results.push({writingMode,direction,first,last,length:node.length,hidden:textIntervals(doc,viewport).some(x=>x.node.data==='HIDDEN')})
   }
   doc.body.style.cssText='margin:0;font:16px monospace';doc.body.innerHTML='<p>abc</p>';const range=doc.createRange();range.setStart(doc.querySelector('p').firstChild,3);range.collapse(true)
   return {results,eofCaret:visibleCaret(doc,range,viewport),sameFontIdentity:JSON.stringify(fontGeometry([doc]))===JSON.stringify(fontGeometry([doc,doc]))}
  })
  for(const value of results.results){assert.equal(value.first,0,JSON.stringify(value));assert.equal(value.last,value.length,JSON.stringify(value));assert(!value.hidden)}
  assert(results.eofCaret);assert(results.sameFontIdentity);assert.deepEqual(errors,[]);console.log(JSON.stringify({ok:true,...results,pageErrors:errors}))
 }finally{await browser.close();server.close()}
})().catch(error=>{console.error(error);server.close();process.exitCode=1})

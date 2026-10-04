/* Actual protocol-2 streaming HTML/preload regression. Uses the production host and vendor. */
const { chromium }=require('playwright')
const http=require('http'),fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict')
const assets=path.resolve(__dirname,'../../app/src/main/assets/reader'),base='/reader/loading/revision/'
const seen=[],errors=[],messages=[],held=[]
const server=http.createServer((req,res)=>{
  const resource=req.url.startsWith(base)?req.url.slice(base.length):''
  const match=/^sections\/(\d+)\.html$/.exec(resource)
  if(match){
    const i=Number(match[1]),texts=Array.from({length:36},(_,n)=>`第${i}:${n}段 中文 English😀 `+'真实段落 '.repeat(180))
    const text=texts.join(''),sha=crypto.createHash('sha256').update(text).digest('hex'),nodes=texts.map(t=>`<p>${t}</p>`)
    seen.push(i)
    // The immediately preceding section is available for genuine backward reading.
    // A second, spare predecessor never responds and must not be requested merely
    // to re-anchor the already loaded primary document.
    if(i===15){held.push(res);return}
    res.writeHead(200,{'Content-Type':'text/html;charset=utf-8','Content-Security-Policy':"default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; frame-src 'none'"})
    res.write(`<html><head><meta charset='utf-8'></head><body data-reader-canonical-length='${text.length}' data-reader-canonical-sha='${sha}'>${nodes.slice(0,2).join('')}`)
    setTimeout(()=>res.end(nodes.slice(2).join('')+'</body></html>'),400)
    return
  }
  if(!['host2.html','host2.js','vendor/foliate/paginator.js'].includes(resource)){res.writeHead(403);res.end();return}
  res.writeHead(200,{'Content-Type':resource.endsWith('.js')?'text/javascript':'text/html'});res.end(fs.readFileSync(path.join(assets,resource)))
})
;(async()=>{
  await new Promise(r=>server.listen(0,'127.0.0.1',r))
  const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true}),page=await browser.newPage({viewport:{width:369,height:576}})
  page.on('pageerror',e=>errors.push(String(e)))
  await page.exposeFunction('receive',raw=>messages.push(JSON.parse(raw)))
  await page.addInitScript(()=>{window.ReaderEvents={postMessage:raw=>window.receive(raw)}})
  try{
    await page.goto(`http://127.0.0.1:${server.address().port}${base}host2.html`);await page.waitForFunction(()=>window.readerHost)
    let id=0
    const rules={profileId:'test',geometry:{gap:'7%','margin-top':'24px','margin-bottom':'24px','margin-left':'16px','margin-right':'16px','max-column-count':1},beforeAuthor:'html,body{margin:0}body{font:20px serif}p{margin:1em 0;line-height:1.6}',afterAuthor:''}
    async function command(type,payload){
      const cmd={protocol:2,sessionId:'loading',revision:'revision',layoutEpoch:++id,commandId:id,intentToken:`command-${id}`,intentCause:type==='setRules'?'Reflow':'Restore',type,payload}
      await page.evaluate(c=>readerHost.command(c),cmd)
      // Read real loading Document indices repeatedly; this must not poison later validation.
      await page.evaluate(()=>{window.probe=setInterval(()=>readerHost.diagnostics(),15)})
      try{await page.waitForFunction(id=>readerHost.diagnostics().events.some(e=>e.commandId===id&&['ready','error'].includes(e.type)),id,{timeout:15000})}
      finally{await page.evaluate(()=>clearInterval(window.probe))}
      const diag=await page.evaluate(()=>readerHost.diagnostics())
      assert.equal(messages.find(e=>e.commandId===id&&e.type==='error'),undefined,JSON.stringify(diag))
      assert(diag.anchorGeometry.visible,JSON.stringify(diag));assert(diag.activeViews<=8)
      assert(diag.contents.every(c=>c.state==='complete'&&c.validated&&Number(c.canonicalLength)===c.indexedLength),JSON.stringify(diag))
    }
    const target={section:17,kind:'TEXT',offset:100}
    await command('open',{...rules,flow:'scrolled',sectionCount:40,target})
    await command('setRules',{...rules,beforeAuthor:rules.beforeAuthor.replace('20px','26px'),flow:'paginated',target})
    assert(seen.includes(16),'necessary preceding section remains loaded');assert(!seen.includes(15),'same-primary Range restoration must not start extra navigation');
    assert.deepEqual(errors,[]);await page.evaluate(()=>readerHost.close())
    console.log(JSON.stringify({ok:true,streamingPreloadAndReflow:true,allReadyDocumentsValidated:true,sparePredecessorDidNotBlock:true,sectionLoads:seen.length,pageErrors:errors}))
  }finally{held.forEach(response=>response.destroy());await browser.close();server.close()}
})().catch(e=>{console.error(e);server.close();process.exitCode=1})

/* Actual compiler CSSOM: package declarations win over zero-specificity defaults;
 * CUSTOM overrides independently sanitized copies, never the original source. */
const {chromium}=require('playwright'),fs=require('fs'),path=require('path'),assert=require('assert/strict')
;(async()=>{
 const vectors=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../../app/build/reports/reader-author-rules.json'),'utf8'))
 const browser=await chromium.launch({executablePath:process.env.READER_CHROMIUM,headless:true})
 try {
  const page=await browser.newPage()
  for(const v of vectors){
   await page.setContent(`<html><head><style>${v.before}</style><style>${v.sheet}</style><style>${v.after}</style></head><body style="${v.inline}"><p>Original 中文 English paragraph</p></body></html>`)
   const actual=await page.locator('p').evaluate(p=>{const s=getComputedStyle(p);return {size:parseFloat(s.fontSize),line:parseFloat(s.lineHeight),color:s.color,family:s.fontFamily,indent:parseFloat(s.textIndent),align:s.textAlign,margin:parseFloat(s.marginTop)}})
   assert.equal(actual.size,v.expectedSize,v.name+' size');assert.equal(actual.line,v.expectedLine,v.name+' line');assert.equal(actual.color,v.expectedColor,v.name+' color')
   assert.equal(actual.family.includes('PackageFont'),v.packageFamily,v.name+' family')
   if(['html','root','body','inline'].some(name=>v.name===name+'-AUTHOR')) {assert.equal(actual.indent,72,v.name+' authored indent');assert.equal(actual.align,'right',v.name+' authored alignment')}
   else if(v.mode==='CUSTOM'||v.name.startsWith('missing-')) {assert.equal(actual.indent,0,v.name+' fallback indent');assert.equal(actual.align,'left',v.name+' fallback alignment');assert.equal(actual.margin,actual.size*.6,v.name+' paragraph margin')}
  }
  console.log(JSON.stringify({ok:true,compilerCssomVectors:vectors.length}))
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1})

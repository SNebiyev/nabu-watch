package scanner

import (
	"fmt"
	"sync"
)

var stealthJSCache sync.Map // primaryLang → JS string

// buildStealthJS returns JavaScript that is injected via
// page.AddScriptToEvaluateOnNewDocument BEFORE any page script runs.
// It overrides browser APIs that WAFs and bot detectors fingerprint:
//
//   - navigator.webdriver (CDP detection — Akamai, PerimeterX)
//   - Canvas toDataURL/toBlob/getImageData (canvas fingerprint)
//   - WebGL getParameter (GPU vendor/renderer)
//   - AudioContext copyFromChannel (audio fingerprint)
//   - chrome.runtime (CDP environment detection)
//   - navigator.permissions.query (headless behavior)
//   - navigator.plugins/mimeTypes (plugin count)
//
// primaryLang is the BCP-47 language code (e.g. "fr", "de") that matches
// the Accept-Language header and CDP locale override.
func buildStealthJS(primaryLang string) string {
	if cached, ok := stealthJSCache.Load(primaryLang); ok {
		return cached.(string)
	}
	js := buildStealthJSUncached(primaryLang)
	stealthJSCache.Store(primaryLang, js)
	return js
}

func buildStealthJSUncached(primaryLang string) string {
	return fmt.Sprintf(`(function(){
  var def=function(obj,prop,val){
    try{
      Object.defineProperty(obj,prop,{get:function(){return val;},configurable:true});
    }catch(e){}
  };

  // ── Core CDP fingerprint ──────────────────────────────────────────
  def(navigator,'webdriver',undefined);

  // ── Language ──────────────────────────────────────────────────────
  def(navigator,'language','%s');
  def(navigator,'languages',['%s','en']);

  // ── Hardware ──────────────────────────────────────────────────────
  def(navigator,'hardwareConcurrency',8);
  def(navigator,'deviceMemory',8);
  def(navigator,'maxTouchPoints',5);

  // ── Screen (iPhone 14 Pro) ────────────────────────────────────────
  def(screen,'width',390);
  def(screen,'height',844);
  def(screen,'availWidth',390);
  def(screen,'availHeight',844);
  def(screen,'colorDepth',24);
  def(screen,'pixelDepth',24);

  // ── Canvas fingerprint noise ──────────────────────────────────────
  // Flips LSB of the R channel — invisible to the eye, changes the hash.
  var _toDataURL=HTMLCanvasElement.prototype.toDataURL;
  var _toBlob=HTMLCanvasElement.prototype.toBlob;
  var _getImageData=CanvasRenderingContext2D.prototype.getImageData;
  var noise=function(c){
    try{
      var ctx=c.getContext('2d');
      if(!ctx)return;
      var img=ctx.getImageData(0,0,c.width,c.height);
      for(var i=0;i<img.data.length;i+=4){img.data[i]^=1;}
      ctx.putImageData(img,0,0);
    }catch(e){}
  };
  HTMLCanvasElement.prototype.toDataURL=function(){
    noise(this);return _toDataURL.apply(this,arguments);
  };
  HTMLCanvasElement.prototype.toBlob=function(){
    noise(this);return _toBlob.apply(this,arguments);
  };
  CanvasRenderingContext2D.prototype.getImageData=function(){
    var r=_getImageData.apply(this,arguments);
    for(var i=0;i<r.data.length;i+=4){r.data[i]^=1;}
    return r;
  };

  // ── WebGL vendor/renderer spoofing ────────────────────────────────
  // Headless Chrome shows "Google SwiftShader"; real browsers show GPU info.
  var _getParameter=WebGLRenderingContext.prototype.getParameter;
  WebGLRenderingContext.prototype.getParameter=function(p){
    if(p===0x9245)return'Google Inc. (Apple)';
    if(p===0x9246)return'ANGLE (Apple, Apple M1 Pro, OpenGL 4.1)';
    return _getParameter.apply(this,arguments);
  };
  try{
    var _getParameter2=WebGL2RenderingContext.prototype.getParameter;
    WebGL2RenderingContext.prototype.getParameter=function(p){
      if(p===0x9245)return'Google Inc. (Apple)';
      if(p===0x9246)return'ANGLE (Apple, Apple M1 Pro, OpenGL 4.1)';
      return _getParameter2.apply(this,arguments);
    };
  }catch(e){}

  // ── AudioContext fingerprint ───────────────────────────────────────
  try{
    var _copyFromChannel=AudioBuffer.prototype.copyFromChannel;
    AudioBuffer.prototype.copyFromChannel=function(dest,ch,off){
      _copyFromChannel.call(this,dest,ch,off||0);
      for(var i=0;i<dest.length;i+=100){dest[i]+=1e-7;}
    };
  }catch(e){}

  // ── chrome.runtime mock ───────────────────────────────────────────
  if(!window.chrome)window.chrome={};
  if(!window.chrome.runtime)window.chrome.runtime={
    connect:function(){return{onMessage:{addListener:function(){}},postMessage:function(){},onDisconnect:{addListener:function(){}}}},
    sendMessage:function(){}
  };

  // ── Permissions API spoofing ──────────────────────────────────────
  try{
    var _query=navigator.permissions.query.bind(navigator.permissions);
    navigator.permissions.query=function(desc){
      if(desc&&desc.name==='notifications'){
        return Promise.resolve({state:'prompt',onchange:null});
      }
      return _query(desc);
    };
  }catch(e){}

  // ── Plugins/MimeTypes spoofing ────────────────────────────────────
  def(navigator,'plugins',{length:5,item:function(){return null;},namedItem:function(){return null;},refresh:function(){}});
  def(navigator,'mimeTypes',{length:4,item:function(){return null;},namedItem:function(){return null;}});
})();`, primaryLang, primaryLang)
}

// Injected by the Android app into the server page (ForgeWeb.java). Exported files (JSON library, playlists)
// are blob: links that the Android download manager cannot fetch: read them here and hand them to ForgeNative.saveFile.
(function () {
  if (window.__forgeBlobPatch || !window.ForgeNative) return;
  window.__forgeBlobPatch = true;

  function save(a) {
    if (!a.href || a.href.indexOf('blob:') !== 0 || !a.hasAttribute('download')) return false;
    fetch(a.href)
      .then(function (r) { return r.blob(); })
      .then(function (b) {
        var reader = new FileReader();
        reader.onload = function () {
          var data = String(reader.result);
          window.ForgeNative.saveFile(a.download || 'forge-audio', b.type || 'application/octet-stream', data.slice(data.indexOf(',') + 1));
        };
        reader.readAsDataURL(b);
      });
    return true;
  }

  // downloadText() in the web app clicks a detached <a download>: patch click() itself.
  var click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (!save(this)) click.call(this);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest && e.target.closest('a');
    if (a && save(a)) e.preventDefault();
  }, true);
})();

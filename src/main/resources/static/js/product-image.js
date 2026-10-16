/* Product photo: pick from gallery / files, or take a photo with the device camera.
   Photos are shrunk in the browser (max 1600 px, JPEG) so they always fit the 2 MB upload limit. */
(function () {
  'use strict';
  var box = document.getElementById('imgPicker');
  if (!box) { return; }
  var $ = function (id) { return document.getElementById(id); };
  var real = $('imgReal'), preview = $('imgPreview'), video = $('imgVideo'), stream = null;
  var T = { err: box.dataset.tCameraError || 'Camera error', ready: box.dataset.tReady || '' };

  function setFile(file) {
    shrink(file).then(function (out) {
      var dt = new DataTransfer(); dt.items.add(out); real.files = dt.files;
      preview.innerHTML = ''; var img = document.createElement('img'); img.alt = ''; img.src = URL.createObjectURL(out); preview.appendChild(img);
      $('imgInfo').textContent = T.ready + ' · ' + Math.round(out.size / 1024) + ' KB';
    });
  }

  // scale down + re-encode so phone photos (5-10 MB) fit the upload limit
  function shrink(file) {
    return new Promise(function (resolve) {
      if (!/^image\//.test(file.type) || file.type === 'image/gif') { resolve(file); return; }
      var url = URL.createObjectURL(file), im = new Image();
      im.onload = function () {
        var max = 1600, r = Math.min(1, max / Math.max(im.width, im.height));
        var c = document.createElement('canvas'); c.width = Math.round(im.width * r); c.height = Math.round(im.height * r);
        c.getContext('2d').drawImage(im, 0, 0, c.width, c.height);
        c.toBlob(function (b) {
          URL.revokeObjectURL(url);
          resolve(b ? new File([b], 'product-' + Date.now() + '.jpg', { type: 'image/jpeg' }) : file);
        }, 'image/jpeg', 0.85);
      };
      im.onerror = function () { URL.revokeObjectURL(url); resolve(file); };
      im.src = url;
    });
  }

  $('imgChoose').addEventListener('click', function () { $('imgGallery').click(); });
  $('imgGallery').addEventListener('change', function (e) { if (e.target.files[0]) { setFile(e.target.files[0]); } });
  $('imgCameraFallback').addEventListener('change', function (e) { if (e.target.files[0]) { setFile(e.target.files[0]); } });

  function stopCamera() {
    if (stream) { stream.getTracks().forEach(function (t) { t.stop(); }); stream = null; }
    video.classList.add('d-none'); preview.classList.remove('d-none');
    $('imgSnap').classList.add('d-none'); $('imgCancel').classList.add('d-none');
    $('imgCamera').classList.remove('d-none'); $('imgChoose').classList.remove('d-none');
  }

  $('imgCamera').addEventListener('click', function () {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) { $('imgCameraFallback').click(); return; }
    navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false }).then(function (s) {
      stream = s; video.srcObject = s; video.classList.remove('d-none'); preview.classList.add('d-none');
      $('imgSnap').classList.remove('d-none'); $('imgCancel').classList.remove('d-none');
      $('imgCamera').classList.add('d-none'); $('imgChoose').classList.add('d-none');
    }).catch(function () {
      // no live camera (denied / not available): phones can still open their camera app through the file picker
      if (window.MCS && MCS.toast) { MCS.toast(T.err, 'warning'); }
      $('imgCameraFallback').click();
    });
  });

  $('imgSnap').addEventListener('click', function () {
    if (!video.videoWidth) { return; }
    var c = document.createElement('canvas'); c.width = video.videoWidth; c.height = video.videoHeight;
    c.getContext('2d').drawImage(video, 0, 0);
    c.toBlob(function (b) { if (b) { setFile(new File([b], 'camera.jpg', { type: 'image/jpeg' })); } stopCamera(); }, 'image/jpeg', 0.9);
  });
  $('imgCancel').addEventListener('click', stopCamera);
  window.addEventListener('pagehide', stopCamera);
})();

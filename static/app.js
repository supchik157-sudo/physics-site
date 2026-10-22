(function () {
  var body = document.body;
  var doc = document.documentElement;

  // меню на телефоне
  var menuBtn = document.getElementById('menu-btn');
  var scrim = document.getElementById('scrim');
  function setNav(open) { body.classList.toggle('nav-open', open); }
  if (menuBtn) {
    menuBtn.addEventListener('click', function () {
      setNav(!body.classList.contains('nav-open'));
    });
  }
  if (scrim) scrim.addEventListener('click', function () { setNav(false); });

  // поиск по темам в боковом меню
  var search = document.getElementById('search');
  var sections = document.querySelectorAll('.nav-section');
  if (search) {
    search.addEventListener('input', function () {
      var q = search.value.trim().toLowerCase();
      sections.forEach(function (section) {
        var heading = section.querySelector('h3').textContent.toLowerCase();
        var sectionHit = q !== '' && heading.indexOf(q) !== -1;
        var any = false;
        section.querySelectorAll('a').forEach(function (a) {
          var hit = q === '' || sectionHit || a.textContent.toLowerCase().indexOf(q) !== -1;
          a.hidden = !hit;
          if (hit) any = true;
        });
        section.hidden = !any;
      });
    });

    document.addEventListener('keydown', function (e) {
      var tag = (document.activeElement && document.activeElement.tagName) || '';
      if (e.key === '/' && !/input|textarea/i.test(tag)) {
        e.preventDefault();
        if (window.matchMedia('(max-width: 860px)').matches) setNav(true);
        search.focus();
      } else if (e.key === 'Escape' && document.activeElement === search) {
        search.value = '';
        search.dispatchEvent(new Event('input'));
        search.blur();
      }
    });
  }

  // показать выбранную тему в меню
  var active = document.querySelector('.nav a.active');
  if (active && active.scrollIntoView) active.scrollIntoView({ block: 'nearest' });

  // полоса прогресса чтения и кнопка «наверх»
  var progress = document.getElementById('progress');
  var toTop = document.getElementById('to-top');
  function onScroll() {
    var top = doc.scrollTop || body.scrollTop || 0;
    var max = doc.scrollHeight - doc.clientHeight;
    var p = max > 0 ? Math.min(1, top / max) : 0;
    if (progress) progress.style.width = (p * 100) + '%';
    if (toTop) toTop.classList.toggle('show', top > 600);
  }
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('resize', onScroll);
  onScroll();
  if (toTop) {
    toTop.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }

  // плавное появление блоков главной страницы при прокрутке
  var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  if ('IntersectionObserver' in window && !reduce) {
    var items = document.querySelectorAll('.feature, .topics-head, .section');
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('in');
          io.unobserve(entry.target);
        }
      });
    }, { threshold: 0.05 });
    items.forEach(function (el, i) {
      el.classList.add('will-reveal');
      el.style.setProperty('--rd', (i % 4) * 70 + 'ms');
      io.observe(el);
    });
  }
})();

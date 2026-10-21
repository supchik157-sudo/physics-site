(function () {
  var body = document.body;

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
})();

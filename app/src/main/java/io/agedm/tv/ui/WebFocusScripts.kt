package io.agedm.tv.ui

object WebFocusScripts {
    val installScript: String =
        """
        (function() {
          if (window.__ageTvInstalled) {
            return;
          }
          window.__ageTvInstalled = true;

          var style = document.createElement('style');
          style.textContent = '.__age_tv_focus{outline:4px solid #6ED9B8 !important; outline-offset:2px !important; border-radius:8px !important;}';
          document.head.appendChild(style);

          var current = null;
          var lastUrl = '';
          var selector = [
            'a',
            'button',
            'input',
            'textarea',
            '[role="button"]',
            '[tabindex]',
            '.van-cell',
            '.van-grid-item',
            '.van-tab',
            '.van-button',
            '.van-search__action'
          ].join(',');

          function visible(el) {
            if (!el || !el.getBoundingClientRect) return false;
            var style = window.getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
            var rect = el.getBoundingClientRect();
            return rect.width > 12 && rect.height > 12;
          }

          function center(rect) {
            return {
              x: rect.left + rect.width / 2,
              y: rect.top + rect.height / 2
            };
          }

          function candidates() {
            return Array.prototype.slice.call(document.querySelectorAll(selector)).filter(visible);
          }

          function normalizeText(text) {
            return String(text || '').replace(/\s+/g, '');
          }

          function visibleText(el) {
            if (!el) return '';
            return normalizeText(el.innerText || el.textContent || '');
          }

          function promoContainerOf(node) {
            var el = node;
            while (el && el !== document.body) {
              var text = visibleText(el);
              if (
                text.indexOf('今天不再显示') > -1 ||
                text.indexOf('强烈建议下载APP') > -1 ||
                text.indexOf('age.tv') > -1 ||
                text.indexOf('agefans.com') > -1 ||
                text.indexOf('Android客户端') > -1 ||
                text.indexOf('iOS客户端') > -1
              ) {
                return el;
              }
              el = el.parentElement;
            }
            return null;
          }

          function clickElement(el) {
            if (!el) return false;
            try {
              ['mouseover', 'mousedown', 'mouseup', 'click'].forEach(function(type) {
                el.dispatchEvent(new MouseEvent(type, {
                  bubbles: true,
                  cancelable: true,
                  view: window
                }));
              });
            } catch (e) {}
            try {
              if (typeof el.click === 'function') {
                el.click();
              }
            } catch (e) {}
            return true;
          }

          function hidePromo(container) {
            if (!container) return false;
            container.style.setProperty('display', 'none', 'important');
            container.style.setProperty('visibility', 'hidden', 'important');
            container.setAttribute('aria-hidden', 'true');
            return true;
          }

          function dismissPromoDialog() {
            var closeTargets = Array.prototype.slice.call(
              document.querySelectorAll('button, a, span, div')
            ).filter(visible);

            var preferred = closeTargets.find(function(el) {
              return visibleText(el).indexOf('今天不再显示') > -1 && promoContainerOf(el);
            });

            if (preferred) {
              clickElement(preferred);
              var promo = promoContainerOf(preferred);
              if (promo) {
                setTimeout(function() { hidePromo(promo); }, 60);
              }
              return true;
            }

            var fallback = closeTargets.find(function(el) {
              var text = visibleText(el);
              if (text !== '关闭' && text.indexOf('关闭') === -1) return false;
              return !!promoContainerOf(el);
            });

            if (fallback) {
              clickElement(fallback);
              var fallbackPromo = promoContainerOf(fallback);
              if (fallbackPromo) {
                setTimeout(function() { hidePromo(fallbackPromo); }, 60);
              }
              return true;
            }

            var overlays = Array.prototype.slice.call(document.querySelectorAll('.van-dialog, .van-overlay, [role="dialog"]'));
            var promoOverlay = overlays.find(function(el) { return !!promoContainerOf(el); });
            if (promoOverlay) {
              return hidePromo(promoOverlay);
            }

            return false;
          }

          function mark(el) {
            if (current && current.classList) {
              current.classList.remove('__age_tv_focus');
            }
            current = el;
            if (!current) return false;
            if (current.classList) {
              current.classList.add('__age_tv_focus');
            }
            try {
              current.focus({ preventScroll: true });
            } catch (e) {
              if (current.focus) current.focus();
            }
            try {
              current.scrollIntoView({ block: 'center', inline: 'center', behavior: 'auto' });
            } catch (e) {
              current.scrollIntoView();
            }
            return true;
          }

          function score(currentRect, targetRect, direction) {
            var c = center(currentRect);
            var t = center(targetRect);
            var primary;
            var cross;
            if (direction === 'left') {
              if (t.x >= c.x - 6) return Infinity;
              primary = c.x - t.x;
              cross = Math.abs(c.y - t.y);
            } else if (direction === 'right') {
              if (t.x <= c.x + 6) return Infinity;
              primary = t.x - c.x;
              cross = Math.abs(c.y - t.y);
            } else if (direction === 'up') {
              if (t.y >= c.y - 6) return Infinity;
              primary = c.y - t.y;
              cross = Math.abs(c.x - t.x);
            } else {
              if (t.y <= c.y + 6) return Infinity;
              primary = t.y - c.y;
              cross = Math.abs(c.x - t.x);
            }
            return primary * 1000 + cross;
          }

          function ensureCurrent(list) {
            if (!current || !document.contains(current) || !visible(current)) {
              return mark(list[0]);
            }
            return true;
          }

          window.AgeTvFocus = {
            reset: function() {
              if (current && current.classList) {
                current.classList.remove('__age_tv_focus');
              }
              current = null;
              return true;
            },
            move: function(direction) {
              var list = candidates();
              if (!list.length) return false;
              ensureCurrent(list);
              var currentRect = current.getBoundingClientRect();
              var best = null;
              var bestScore = Infinity;
              list.forEach(function(el) {
                if (el === current) return;
                var value = score(currentRect, el.getBoundingClientRect(), direction);
                if (value < bestScore) {
                  best = el;
                  bestScore = value;
                }
              });
              if (!best) return false;
              return mark(best);
            },
            activate: function() {
              var list = candidates();
              if (!list.length) return false;
              ensureCurrent(list);
              if (!current) return false;
              if (current.click) {
                current.click();
              }
              return true;
            }
          };

          function notifyRoute() {
            var href = window.location.href;
            if (href === lastUrl) return;
            lastUrl = href;
            if (window.AgeBridge && window.AgeBridge.onRouteChanged) {
              window.AgeBridge.onRouteChanged(href);
            }
            setTimeout(function() {
              window.AgeTvFocus.reset();
            }, 50);
          }

          window.addEventListener('hashchange', notifyRoute, true);
          var originalPushState = history.pushState;
          history.pushState = function() {
            var value = originalPushState.apply(history, arguments);
            setTimeout(notifyRoute, 0);
            return value;
          };
          var originalReplaceState = history.replaceState;
          history.replaceState = function() {
            var value = originalReplaceState.apply(history, arguments);
            setTimeout(notifyRoute, 0);
            return value;
          };

          document.addEventListener('DOMContentLoaded', function() {
            notifyRoute();
          });
          var dismissObserver = new MutationObserver(function() {
            dismissPromoDialog();
          });
          dismissObserver.observe(document.documentElement || document.body, {
            childList: true,
            subtree: true,
            attributes: false
          });
          setInterval(dismissPromoDialog, 800);
          setTimeout(dismissPromoDialog, 0);
          setTimeout(dismissPromoDialog, 300);
          setTimeout(dismissPromoDialog, 1000);
          setInterval(notifyRoute, 500);
          setTimeout(notifyRoute, 0);
        })();
        """.trimIndent()

    fun moveScript(direction: String): String {
        return "window.AgeTvFocus && window.AgeTvFocus.move('$direction');"
    }

    const val ACTIVATE_SCRIPT = "window.AgeTvFocus && window.AgeTvFocus.activate();"
}

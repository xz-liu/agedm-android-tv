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
          style.textContent = '.__age_tv_focus{outline:4px solid #6ED9B8 !important; outline-offset:2px !important; border-radius:8px !important;}.foot-container,.van-tabbar--fixed{display:none !important;}.body-container.foot-active{padding-bottom:0 !important;}';
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

          function matches(el, selector) {
            if (!el || !selector) return false;
            var fn = el.matches || el.webkitMatchesSelector || el.msMatchesSelector;
            if (!fn) return false;
            return fn.call(el, selector);
          }

          function activationTarget(el) {
            if (!el) return null;

            var directSelector = 'a[href],button,input,textarea,[role="button"],.van-button,.van-tab,.van-tabbar-item';
            if (matches(el, directSelector)) {
              return el;
            }

            var nested = Array.prototype.slice.call(
              el.querySelectorAll(
                [
                  'a[href]',
                  'button',
                  'input',
                  'textarea',
                  '[role="button"]',
                  '.van-button',
                  '.van-cell__title a',
                  '.van-grid-item__content a',
                  '.video-item-title a',
                  '.van-tab',
                  '.van-tabbar-item'
                ].join(',')
              )
            ).find(visible);
            if (nested) {
              return nested;
            }

            if (el.closest) {
              var parentTarget = el.closest(directSelector);
              if (parentTarget && visible(parentTarget)) {
                return parentTarget;
              }
            }

            return el;
          }

          function hideElement(el) {
            if (!el) return false;
            el.style.setProperty('display', 'none', 'important');
            el.style.setProperty('visibility', 'hidden', 'important');
            el.setAttribute('aria-hidden', 'true');
            return true;
          }

          function dismissPromoDialog() {
            var dialog = document.querySelector('.age-update-dialog');
            if (!dialog || !visible(dialog)) return false;

            var dialogText = visibleText(dialog);
            var looksLikePromo =
              dialogText.indexOf('今天不再显示') > -1 &&
              (
                dialogText.indexOf('强烈建议下载APP') > -1 ||
                dialogText.indexOf('age.tv') > -1 ||
                dialogText.indexOf('agefans.com') > -1
              );

            if (!looksLikePromo) return false;

            var closeButton = dialog.querySelector('.van-dialog__confirm');
            if (closeButton && visible(closeButton)) {
              clickElement(closeButton);
              setTimeout(function() {
                if (document.contains(dialog) && visible(dialog)) {
                  hideElement(dialog);
                  var overlay = document.querySelector('.van-overlay');
                  if (overlay && visible(overlay)) {
                    hideElement(overlay);
                  }
                }
              }, 120);
              return true;
            }

            hideElement(dialog);
            var fallbackOverlay = document.querySelector('.van-overlay');
            if (fallbackOverlay && visible(fallbackOverlay)) {
              hideElement(fallbackOverlay);
            }
            return true;
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
              return clickElement(activationTarget(current));
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

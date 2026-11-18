/**
 * Non-blocking Toast Notifications & Promise-based Confirmation Dialogs.
 * Pure Vanilla ES6, zero external dependencies.
 */
import { escapeHtml } from './dom.js';

const INITIALIZED_MODALS = new WeakSet();
let activeDialogSession = null;
const pendingDialogQueue = [];

function ensureToastContainer() {
  let container = document.getElementById('toast-container');
  if (container) return container;

  container = document.createElement('div');
  container.id = 'toast-container';
  container.className = 'toast-container';
  container.setAttribute('aria-live', 'polite');
  container.setAttribute('aria-atomic', 'false');
  document.body.appendChild(container);
  return container;
}

function ensureConfirmModal() {
  let modal = document.getElementById('modal-confirm');
  if (modal) return modal;

  modal = document.createElement('div');
  modal.id = 'modal-confirm';
  modal.className = 'modal-backdrop hidden';
  modal.setAttribute('role', 'dialog');
  modal.setAttribute('aria-modal', 'true');
  modal.setAttribute('aria-labelledby', 'confirm-modal-title');
  modal.setAttribute('data-testid', 'modal-confirm');
  modal.style.zIndex = '1100';
  modal.innerHTML =
    '<div class="modal-dialog modal-dialog-confirm" style="max-width: 440px; margin: auto;">' +
    '  <div class="modal-header">' +
    '    <div class="modal-title-group">' +
    '      <h3 id="confirm-modal-title">Confirm</h3>' +
    '    </div>' +
    '    <button type="button" id="btn-close-confirm-modal" class="btn btn-tiny modal-close-btn" title="Close" aria-label="Close">&times;</button>' +
    '  </div>' +
    '  <div class="modal-body" style="padding: 16px 20px;">' +
    '    <p id="confirm-modal-message" style="margin: 0; font-size: 0.9rem; color: var(--text-main); white-space: pre-wrap;"></p>' +
    '  </div>' +
    '  <div class="modal-footer">' +
    '    <button type="button" id="btn-confirm-cancel" class="btn btn-secondary" data-testid="btn-confirm-cancel">Cancel</button>' +
    '    <button type="button" id="btn-confirm-secondary" class="btn btn-secondary hidden" data-testid="btn-confirm-secondary"></button>' +
    '    <button type="button" id="btn-confirm-accept" class="btn btn-primary" data-testid="btn-confirm-accept">Confirm</button>' +
    '  </div>' +
    '</div>';
  document.body.appendChild(modal);
  return modal;
}

/**
 * Displays a non-blocking toast notification.
 * @param {string} message - Plain text notification message (HTML-escaped).
 * @param {{ type?: string, durationMs?: number }} [options]
 * @returns {HTMLElement} The created toast element.
 */
export function showToast(message, options = {}) {
  const { type = 'info', durationMs = 3500 } = options || {};
  const container = ensureToastContainer();
  const safeType =
    String(type || 'info')
      .toLowerCase()
      .replace(/[^a-z0-9_-]/g, '') || 'info';

  const toast = document.createElement('div');
  toast.className = `toast-item toast-${safeType}`;
  toast.setAttribute('data-testid', 'toast-item');
  toast.setAttribute('role', 'status');
  toast.innerHTML =
    `<span class="toast-message">${escapeHtml(message ?? '')}</span>` +
    '<button type="button" class="toast-close" aria-label="Dismiss notification">&times;</button>';

  let timerId = null;
  let removed = false;
  const removeToast = () => {
    if (removed) return;
    removed = true;
    if (timerId !== null) {
      clearTimeout(timerId);
      timerId = null;
    }
    toast.remove();
  };

  const closeBtn = toast.querySelector('.toast-close');
  if (closeBtn) {
    closeBtn.addEventListener('click', removeToast);
  }

  container.appendChild(toast);

  if (typeof durationMs === 'number' && durationMs > 0) {
    timerId = setTimeout(removeToast, durationMs);
  }

  return toast;
}

function openConfirmSession(options, resolve) {
  const {
    title = 'Confirm',
    message = '',
    confirmLabel = 'Confirm',
    cancelLabel = 'Cancel',
    secondaryLabel = null,
    danger = false
  } = options || {};

  const modal = ensureConfirmModal();
  const titleEl = document.getElementById('confirm-modal-title');
  const messageEl = document.getElementById('confirm-modal-message');
  const acceptBtn = document.getElementById('btn-confirm-accept');
  const secondaryBtn = document.getElementById('btn-confirm-secondary');
  const cancelBtn = document.getElementById('btn-confirm-cancel');
  const closeBtn = document.getElementById('btn-close-confirm-modal');

  if (titleEl) {
    titleEl.textContent = title == null ? 'Confirm' : String(title);
  }
  if (messageEl) {
    messageEl.textContent = message == null ? '' : String(message);
  }
  if (acceptBtn) {
    acceptBtn.textContent = confirmLabel == null ? 'Confirm' : String(confirmLabel);
    if (danger) {
      acceptBtn.classList.remove('btn-primary');
      acceptBtn.classList.add('btn-danger');
    } else {
      acceptBtn.classList.remove('btn-danger');
      acceptBtn.classList.add('btn-primary');
    }
  }
  if (cancelBtn) {
    cancelBtn.textContent = cancelLabel == null ? 'Cancel' : String(cancelLabel);
  }
  if (secondaryBtn) {
    if (secondaryLabel) {
      secondaryBtn.textContent = String(secondaryLabel);
      secondaryBtn.classList.remove('hidden');
    } else {
      secondaryBtn.textContent = '';
      secondaryBtn.classList.add('hidden');
    }
  }

  let settled = false;

  const onAccept = () => finish('confirm');
  const onSecondary = () => finish('secondary');
  const onCancel = () => finish('cancel');
  const onBackdropClick = e => {
    if (e.target === modal) {
      finish('cancel');
    }
  };
  const onKeyDown = e => {
    if (e.key === 'Escape') {
      e.preventDefault();
      finish('cancel');
    }
  };

  const cleanup = () => {
    if (acceptBtn) acceptBtn.removeEventListener('click', onAccept);
    if (secondaryBtn) secondaryBtn.removeEventListener('click', onSecondary);
    if (cancelBtn) cancelBtn.removeEventListener('click', onCancel);
    if (closeBtn) closeBtn.removeEventListener('click', onCancel);
    modal.removeEventListener('click', onBackdropClick);
    document.removeEventListener('keydown', onKeyDown);
    modal.classList.add('hidden');
  };

  function finish(outcome) {
    if (settled) return;
    settled = true;
    cleanup();
    activeDialogSession = null;
    resolve(outcome);
    if (pendingDialogQueue.length > 0) {
      const next = pendingDialogQueue.shift();
      openConfirmSession(next.options, next.resolve);
    }
  }

  activeDialogSession = { finish, cleanup };

  if (acceptBtn) acceptBtn.addEventListener('click', onAccept);
  if (secondaryBtn && secondaryLabel) secondaryBtn.addEventListener('click', onSecondary);
  if (cancelBtn) cancelBtn.addEventListener('click', onCancel);
  if (closeBtn) closeBtn.addEventListener('click', onCancel);
  modal.addEventListener('click', onBackdropClick);
  document.addEventListener('keydown', onKeyDown);

  modal.classList.remove('hidden');
  if (acceptBtn) {
    acceptBtn.focus();
  }
}

/**
 * Displays an async confirmation dialog resolving to 'confirm', 'secondary', or 'cancel'.
 * @param {{
 *   title?: string,
 *   message?: string,
 *   confirmLabel?: string,
 *   cancelLabel?: string,
 *   secondaryLabel?: string | null,
 *   danger?: boolean
 * }} [options]
 * @returns {Promise<'confirm' | 'secondary' | 'cancel'>}
 */
export function confirmDialog(options = {}) {
  return new Promise(resolve => {
    if (activeDialogSession) {
      pendingDialogQueue.push({ options, resolve });
      return;
    }
    openConfirmSession(options, resolve);
  });
}

/**
 * Wires static close/escape handling on #modal-confirm if it exists in the DOM.
 */
export function initDialogSystem() {
  const modal = document.getElementById('modal-confirm');
  if (!modal || INITIALIZED_MODALS.has(modal)) {
    return;
  }
  INITIALIZED_MODALS.add(modal);

  const dismissIfStatic = () => {
    if (activeDialogSession) {
      activeDialogSession.finish('cancel');
      return;
    }
    modal.classList.add('hidden');
  };

  const closeBtn = document.getElementById('btn-close-confirm-modal');
  const cancelBtn = document.getElementById('btn-confirm-cancel');
  if (closeBtn) {
    closeBtn.addEventListener('click', dismissIfStatic);
  }
  if (cancelBtn) {
    cancelBtn.addEventListener('click', dismissIfStatic);
  }
  modal.addEventListener('click', e => {
    if (e.target === modal) {
      dismissIfStatic();
    }
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !modal.classList.contains('hidden')) {
      e.preventDefault();
      dismissIfStatic();
    }
  });
}

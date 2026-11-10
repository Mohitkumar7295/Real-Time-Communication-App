/**
 * MeetApp — Authentication Controller & Form Manager (auth.js)
 * Form validation, two-step OTP workflow, token storage, and session lifecycle.
 */

import { apiRequest, ApiError } from './api.js';

// ==========================================================================
// Session & Token Management
// ==========================================================================

const TOKEN_KEY = 'meetapp_token';
const USER_KEY = 'meetapp_user';

/**
 * Security Tradeoff Note:
 * Storing the JWT in sessionStorage provides session-bound isolation across browser tabs
 * and guarantees tokens are cleared immediately when the browser tab or window closes.
 * While sessionStorage is theoretically accessible to script in the event of an XSS defect,
 * MeetApp mitigates this by strictly prohibiting innerHTML, enforcing a tight Content
 * Security Policy (CSP), and avoiding third-party scripts.
 */
export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY);
}

export function getUser() {
  try {
    const raw = sessionStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function setSession(token, user) {
  if (token) {
    sessionStorage.setItem(TOKEN_KEY, token);
  }
  if (user) {
    sessionStorage.setItem(USER_KEY, JSON.stringify(user));
  }
}

export function clearSession() {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(USER_KEY);
}

export function isAuthenticated() {
  return Boolean(getToken());
}

// ==========================================================================
// DOM Utilities (Never using innerHTML with dynamic content)
// ==========================================================================

function showAlert(alertEl, message, isError = true) {
  if (!alertEl) return;
  alertEl.textContent = message;
  alertEl.className = isError ? 'alert-banner alert-error active' : 'alert-banner alert-success active';
}

function hideAlert(alertEl) {
  if (!alertEl) return;
  alertEl.textContent = '';
  alertEl.className = 'alert-banner';
}

function setFieldError(inputEl, errorEl, message) {
  if (!inputEl) return;
  if (message) {
    inputEl.setAttribute('aria-invalid', 'true');
    if (errorEl) {
      errorEl.textContent = message;
      errorEl.classList.add('active');
    }
  } else {
    inputEl.removeAttribute('aria-invalid');
    if (errorEl) {
      errorEl.textContent = '';
      errorEl.classList.remove('active');
    }
  }
}

function setButtonLoading(btn, isLoading, originalText = '') {
  if (!btn) return;
  if (isLoading) {
    btn.disabled = true;
    btn.classList.add('btn-loading');
    btn.setAttribute('aria-busy', 'true');
  } else {
    btn.disabled = false;
    btn.classList.remove('btn-loading');
    btn.removeAttribute('aria-busy');
    if (originalText) {
      btn.textContent = originalText;
    }
  }
}

// ==========================================================================
// Validation Helpers (Conforming to docs/API.md and PRD)
// ==========================================================================

const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PASSWORD_LETTER_REGEX = /[A-Za-z]/;
const PASSWORD_NUMBER_REGEX = /\d/;

export function validateName(name) {
  const trimmed = (name || '').trim();
  if (!trimmed) return 'Name is required.';
  if (trimmed.length < 2) return 'Name must be at least 2 characters.';
  if (trimmed.length > 50) return 'Name cannot exceed 50 characters.';
  return '';
}

export function validateEmail(email) {
  const trimmed = (email || '').trim();
  if (!trimmed) return 'Email is required.';
  if (!EMAIL_REGEX.test(trimmed)) return 'Please enter a valid email address.';
  return '';
}

export function validatePassword(password) {
  if (!password) return 'Password is required.';
  if (password.length < 8) return 'Password must be at least 8 characters.';
  if (!PASSWORD_LETTER_REGEX.test(password)) return 'Password must include at least one letter.';
  if (!PASSWORD_NUMBER_REGEX.test(password)) return 'Password must include at least one number.';
  return '';
}

export function validatePasswordConfirm(password, confirm) {
  if (!confirm) return 'Please confirm your password.';
  if (password !== confirm) return 'Passwords do not match.';
  return '';
}

// ==========================================================================
// Password Show / Hide Toggle Binding
// ==========================================================================

function initPasswordToggles() {
  const toggles = document.querySelectorAll('.password-toggle-btn');
  toggles.forEach((btn) => {
    btn.addEventListener('click', () => {
      const targetId = btn.getAttribute('data-target');
      const input = document.getElementById(targetId);
      if (!input) return;

      const isPassword = input.type === 'password';
      input.type = isPassword ? 'text' : 'password';
      btn.setAttribute('aria-pressed', String(isPassword));
      btn.setAttribute('aria-label', isPassword ? 'Hide password' : 'Show password');

      // Swap toggle icon state
      const iconShow = btn.querySelector('.icon-show');
      const iconHide = btn.querySelector('.icon-hide');
      if (iconShow && iconHide) {
        iconShow.style.display = isPassword ? 'none' : 'block';
        iconHide.style.display = isPassword ? 'block' : 'none';
      }
    });
  });
}

// ==========================================================================
// Signup Page Logic
// ==========================================================================

function initSignupPage() {
  const form = document.getElementById('signup-form');
  if (!form) return;

  const alertBox = document.getElementById('auth-alert');
  const nameInput = document.getElementById('name');
  const nameError = document.getElementById('name-error');
  const emailInput = document.getElementById('email');
  const emailError = document.getElementById('email-error');
  const passwordInput = document.getElementById('password');
  const passwordError = document.getElementById('password-error');
  const passwordHint = document.getElementById('password-hint');
  const confirmInput = document.getElementById('confirmPassword');
  const confirmError = document.getElementById('confirmPassword-error');
  const submitBtn = document.getElementById('signup-submit');

  // Real-time password requirement hint update
  passwordInput.addEventListener('input', () => {
    const val = passwordInput.value;
    const hasLength = val.length >= 8;
    const hasLetter = PASSWORD_LETTER_REGEX.test(val);
    const hasNumber = PASSWORD_NUMBER_REGEX.test(val);

    if (passwordHint) {
      if (hasLength && hasLetter && hasNumber) {
        passwordHint.textContent = 'Password meets all requirements.';
        passwordHint.className = 'password-hint valid';
      } else {
        passwordHint.textContent = 'At least 8 characters with a letter and a number.';
        passwordHint.className = 'password-hint';
      }
    }
    setFieldError(passwordInput, passwordError, '');
  });

  // Blur validation
  nameInput.addEventListener('blur', () => setFieldError(nameInput, nameError, validateName(nameInput.value)));
  nameInput.addEventListener('input', () => setFieldError(nameInput, nameError, ''));

  emailInput.addEventListener('blur', () => setFieldError(emailInput, emailError, validateEmail(emailInput.value)));
  emailInput.addEventListener('input', () => setFieldError(emailInput, emailError, ''));

  passwordInput.addEventListener('blur', () => setFieldError(passwordInput, passwordError, validatePassword(passwordInput.value)));

  confirmInput.addEventListener('blur', () =>
    setFieldError(confirmInput, confirmError, validatePasswordConfirm(passwordInput.value, confirmInput.value))
  );
  confirmInput.addEventListener('input', () => setFieldError(confirmInput, confirmError, ''));

  // Submit Handler
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    hideAlert(alertBox);

    const nameErr = validateName(nameInput.value);
    const emailErr = validateEmail(emailInput.value);
    const passErr = validatePassword(passwordInput.value);
    const confirmErr = validatePasswordConfirm(passwordInput.value, confirmInput.value);

    setFieldError(nameInput, nameError, nameErr);
    setFieldError(emailInput, emailError, emailErr);
    setFieldError(passwordInput, passwordError, passErr);
    setFieldError(confirmInput, confirmError, confirmErr);

    // Focus first invalid field
    const firstInvalid = [
      { err: nameErr, el: nameInput },
      { err: emailErr, el: emailInput },
      { err: passErr, el: passwordInput },
      { err: confirmErr, el: confirmInput }
    ].find((item) => item.err);

    if (firstInvalid) {
      firstInvalid.el.focus();
      return;
    }

    const payload = {
      name: nameInput.value.trim(),
      email: emailInput.value.trim().toLowerCase(),
      password: passwordInput.value
    };

    setButtonLoading(submitBtn, true);

    try {
      await apiRequest('/auth/register', {
        method: 'POST',
        body: payload
      });

      // Clear password memory
      passwordInput.value = '';
      confirmInput.value = '';

      showAlert(alertBox, 'Account created successfully. Redirecting to login...', false);

      setTimeout(() => {
        window.location.href = 'login.html';
      }, 1200);
    } catch (err) {
      setButtonLoading(submitBtn, false);
      if (err instanceof ApiError) {
        showAlert(alertBox, err.message, true);
      } else {
        showAlert(alertBox, 'Unable to connect to the server. Please check your connection.', true);
      }
    }
  });
}

// ==========================================================================
// Login Page Logic (Two-Step Workflow in Single Page)
// ==========================================================================

function initLoginPage() {
  const step1 = document.getElementById('login-step-1');
  const step2 = document.getElementById('login-step-2');
  if (!step1 || !step2) return;

  const loginAlert = document.getElementById('login-alert');
  const otpAlert = document.getElementById('otp-alert');

  // Step 1 Elements
  const loginForm = document.getElementById('login-form');
  const emailInput = document.getElementById('login-email');
  const emailError = document.getElementById('login-email-error');
  const passwordInput = document.getElementById('login-password');
  const passwordError = document.getElementById('login-password-error');
  const loginSubmit = document.getElementById('login-submit');

  // Step 2 Elements
  const otpForm = document.getElementById('otp-form');
  const otpInputs = Array.from(document.querySelectorAll('.otp-digit'));
  const otpSubmit = document.getElementById('otp-submit');
  const otpTargetEmail = document.getElementById('otp-target-email');
  const resendBtn = document.getElementById('resend-otp-btn');
  const resendCountdown = document.getElementById('resend-countdown');
  const backToStep1Btn = document.getElementById('back-to-step1');

  let currentChallengeId = null;
  let countdownTimer = null;
  let countdownSeconds = 60;

  // Real-time clearance of validation errors
  emailInput.addEventListener('input', () => setFieldError(emailInput, emailError, ''));
  passwordInput.addEventListener('input', () => setFieldError(passwordInput, passwordError, ''));

  // Start 60-second cooldown timer for Resend button
  function startResendCountdown(duration = 60) {
    if (countdownTimer) clearInterval(countdownTimer);
    countdownSeconds = duration;
    resendBtn.disabled = true;

    function update() {
      if (countdownSeconds <= 0) {
        clearInterval(countdownTimer);
        resendBtn.disabled = false;
        resendCountdown.textContent = '';
      } else {
        resendCountdown.textContent = ` (${countdownSeconds}s)`;
        countdownSeconds--;
      }
    }
    update();
    countdownTimer = setInterval(update, 1000);
  }

  // Switch to Step 2
  function showStep2(challengeId, maskedEmail, expiresInSeconds = 300) {
    currentChallengeId = challengeId;
    step1.hidden = true;
    step2.hidden = false;
    hideAlert(otpAlert);

    if (otpTargetEmail) {
      otpTargetEmail.textContent = maskedEmail || 'your email';
    }

    // Reset and focus OTP digits
    otpInputs.forEach((input) => {
      input.value = '';
      input.removeAttribute('aria-invalid');
    });
    if (otpInputs[0]) {
      otpInputs[0].focus();
    }

    startResendCountdown(60);
  }

  // Switch back to Step 1
  function showStep1(preserveEmail = true) {
    if (countdownTimer) clearInterval(countdownTimer);
    currentChallengeId = null;
    step2.hidden = true;
    step1.hidden = false;
    hideAlert(loginAlert);

    passwordInput.value = '';
    if (!preserveEmail) {
      emailInput.value = '';
    }
    passwordInput.focus();
  }

  // Step 1: Submit email & password
  loginForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    hideAlert(loginAlert);

    const emailErr = validateEmail(emailInput.value);
    const passErr = !passwordInput.value ? 'Password is required.' : '';

    setFieldError(emailInput, emailError, emailErr);
    setFieldError(passwordInput, passwordError, passErr);

    if (emailErr) {
      emailInput.focus();
      return;
    }
    if (passErr) {
      passwordInput.focus();
      return;
    }

    const payload = {
      email: emailInput.value.trim().toLowerCase(),
      password: passwordInput.value
    };

    setButtonLoading(loginSubmit, true);

    try {
      const response = await apiRequest('/auth/login', {
        method: 'POST',
        body: payload
      });

      setButtonLoading(loginSubmit, false);

      if (response && response.otpRequired) {
        // Clear plaintext password from form immediately
        passwordInput.value = '';
        showStep2(response.challengeId, response.maskedEmail, response.expiresInSeconds);
      } else {
        showAlert(loginAlert, 'Unexpected response from server. Please try again.', true);
      }
    } catch (err) {
      setButtonLoading(loginSubmit, false);
      passwordInput.value = '';
      if (err instanceof ApiError) {
        // Generic error message conforming to docs/API.md & docs/SECURITY.md
        showAlert(loginAlert, err.message || 'Email or password is incorrect.', true);
      } else {
        showAlert(loginAlert, 'Unable to connect to the server. Please check your connection.', true);
      }
    }
  });

  // Step 2: Configure 6-digit OTP Inputs (auto-advance, backspace, paste)
  otpInputs.forEach((input, index) => {
    input.addEventListener('input', (e) => {
      const val = input.value;
      // Allow only numbers
      const sanitized = val.replace(/\D/g, '');
      input.value = sanitized ? sanitized[sanitized.length - 1] : '';

      input.removeAttribute('aria-invalid');
      hideAlert(otpAlert);

      if (input.value && index < otpInputs.length - 1) {
        otpInputs[index + 1].focus();
      }
    });

    input.addEventListener('keydown', (e) => {
      if (e.key === 'Backspace') {
        if (!input.value && index > 0) {
          otpInputs[index - 1].focus();
          otpInputs[index - 1].value = '';
          e.preventDefault();
        }
      } else if (e.key === 'ArrowLeft' && index > 0) {
        otpInputs[index - 1].focus();
      } else if (e.key === 'ArrowRight' && index < otpInputs.length - 1) {
        otpInputs[index + 1].focus();
      }
    });

    // Handle full paste into any digit input
    input.addEventListener('paste', (e) => {
      e.preventDefault();
      const pasteData = (e.clipboardData || window.clipboardData).getData('text') || '';
      const digits = pasteData.replace(/\D/g, '').slice(0, 6);
      if (!digits) return;

      digits.split('').forEach((d, i) => {
        if (otpInputs[i]) {
          otpInputs[i].value = d;
          otpInputs[i].removeAttribute('aria-invalid');
        }
      });

      const nextFocus = Math.min(digits.length, 5);
      if (digits.length === 6) {
        otpSubmit.focus();
      } else {
        otpInputs[nextFocus].focus();
      }
    });
  });

  // Step 2: Verify OTP
  otpForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    hideAlert(otpAlert);

    const enteredOtp = otpInputs.map((i) => i.value).join('');
    if (enteredOtp.length < 6) {
      otpInputs.forEach((i) => {
        if (!i.value) i.setAttribute('aria-invalid', 'true');
      });
      showAlert(otpAlert, 'Please enter all 6 digits of your verification code.', true);
      const emptyIdx = otpInputs.findIndex((i) => !i.value);
      if (emptyIdx !== -1) otpInputs[emptyIdx].focus();
      return;
    }

    setButtonLoading(otpSubmit, true);

    try {
      const response = await apiRequest('/auth/verify-otp', {
        method: 'POST',
        body: {
          challengeId: currentChallengeId,
          otp: enteredOtp
        }
      });

      // Clear entered OTP memory
      otpInputs.forEach((i) => (i.value = ''));

      // Save token and profile to session
      setSession(response.token, response.user);

      showAlert(otpAlert, 'Verification successful. Entering MeetApp...', false);

      setTimeout(() => {
        window.location.href = 'dashboard.html';
      }, 600);
    } catch (err) {
      setButtonLoading(otpSubmit, false);
      otpInputs.forEach((i) => {
        i.setAttribute('aria-invalid', 'true');
        i.value = '';
      });
      if (otpInputs[0]) otpInputs[0].focus();

      if (err instanceof ApiError) {
        // If locked due to 5 failed attempts, return to Step 1 with clear notice
        if (err.status === 429 || (err.message && err.message.toLowerCase().includes('locked'))) {
          showStep1(true);
          showAlert(loginAlert, 'Too many failed verification attempts. Please log in again.', true);
        } else {
          showAlert(otpAlert, err.message, true);
        }
      } else {
        showAlert(otpAlert, 'Unable to verify code. Please check your connection.', true);
      }
    }
  });

  // Step 2: Resend OTP
  resendBtn.addEventListener('click', async () => {
    hideAlert(otpAlert);
    if (resendBtn.disabled || !currentChallengeId) return;

    resendBtn.disabled = true;

    try {
      const response = await apiRequest('/auth/resend-otp', {
        method: 'POST',
        body: {
          challengeId: currentChallengeId
        }
      });

      startResendCountdown(60);
      showAlert(otpAlert, response.message || 'A new verification code has been dispatched.', false);
    } catch (err) {
      resendBtn.disabled = false;
      if (err instanceof ApiError) {
        showAlert(otpAlert, err.message, true);
      } else {
        showAlert(otpAlert, 'Failed to resend code. Please try again.', true);
      }
    }
  });

  // Back to Step 1 Link
  backToStep1Btn.addEventListener('click', (e) => {
    e.preventDefault();
    showStep1(true);
  });
}

// ==========================================================================
// Dashboard Page Logic (Mini Placeholder Test Harness)
// ==========================================================================

function initDashboardPage() {
  const welcomeName = document.getElementById('user-welcome-name');
  const userEmail = document.getElementById('user-email');
  const logoutBtn = document.getElementById('logout-btn');

  if (!welcomeName && !logoutBtn) return;

  if (!isAuthenticated()) {
    window.location.href = 'login.html';
    return;
  }

  const user = getUser();
  if (user) {
    if (welcomeName) welcomeName.textContent = user.name || 'User';
    if (userEmail) userEmail.textContent = user.email || '';
  }

  if (logoutBtn) {
    logoutBtn.addEventListener('click', () => {
      clearSession();
      window.location.href = 'login.html';
    });
  }

  const startMeetingBtn = document.getElementById('start-meeting-placeholder');
  if (startMeetingBtn) {
    startMeetingBtn.addEventListener('click', () => {
      alert('Meeting room creation will be connected in Phase 4.');
    });
  }
}

// ==========================================================================
// Page Router & Initialization
// ==========================================================================

if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  document.addEventListener('DOMContentLoaded', () => {
    const path = window.location.pathname.toLowerCase();

    // If already authenticated and visiting login or signup, redirect to dashboard
    if (isAuthenticated() && (path.endsWith('login.html') || path.endsWith('signup.html'))) {
      window.location.href = 'dashboard.html';
      return;
    }

    initPasswordToggles();
    initSignupPage();
    initLoginPage();
    initDashboardPage();
  });
}

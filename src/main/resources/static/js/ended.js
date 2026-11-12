const params = new URLSearchParams(window.location.search);
const reason = params.get('reason');
if (reason) {
  const el = document.getElementById('endedReason');
  if (el) {
    el.textContent = reason;
  }
}

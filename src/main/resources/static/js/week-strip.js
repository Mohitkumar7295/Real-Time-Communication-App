/**
 * MeetApp — Week Strip Component (week-strip.js)
 * Monday-to-Sunday navigation with dynamic activity indicators.
 */

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export class WeekStrip {
  constructor(containerEl, onDaySelected) {
    this.container = containerEl;
    this.onDaySelected = onDaySelected;
    this.currentMonday = this._getMonday(new Date());
    this.selectedDate = new Date();
    this.meetings = [];
  }

  _getMonday(d) {
    const date = new Date(d);
    const day = date.getDay();
    const diff = date.getDate() - day + (day === 0 ? -6 : 1); // Adjust when Sunday
    date.setDate(diff);
    date.setHours(0, 0, 0, 0);
    return date;
  }

  setMeetings(meetings) {
    this.meetings = meetings || [];
    this.render();
  }

  getSelectedDate() {
    return new Date(this.selectedDate);
  }

  getWeekRange() {
    const from = new Date(this.currentMonday);
    const to = new Date(this.currentMonday);
    to.setDate(to.getDate() + 6);
    to.setHours(23, 59, 59, 999);
    return { from, to };
  }

  prevWeek() {
    this.currentMonday.setDate(this.currentMonday.getDate() - 7);
    this.selectedDate = new Date(this.currentMonday);
    this.render();
    if (this.onDaySelected) this.onDaySelected(this.getSelectedDate());
  }

  nextWeek() {
    this.currentMonday.setDate(this.currentMonday.getDate() + 7);
    this.selectedDate = new Date(this.currentMonday);
    this.render();
    if (this.onDaySelected) this.onDaySelected(this.getSelectedDate());
  }

  render() {
    if (!this.container) return;
    this.container.textContent = ''; // Strict clear

    const todayStr = new Date().toDateString();
    const selectedStr = this.selectedDate.toDateString();

    for (let i = 0; i < 7; i++) {
      const d = new Date(this.currentMonday);
      d.setDate(d.getDate() + i);

      const dStr = d.toDateString();
      const isSelected = dStr === selectedStr;
      const isToday = dStr === todayStr;

      // Check if this date has any recorded meetings
      const hasMeetings = this.meetings.some(m => {
        if (!m.createdAt) return false;
        return new Date(m.createdAt).toDateString() === dStr;
      });

      const pill = document.createElement('button');
      pill.type = 'button';
      pill.className = `day-pill ${isSelected ? 'active' : ''}`;
      pill.setAttribute('aria-pressed', isSelected ? 'true' : 'false');
      pill.setAttribute('aria-label', `${DAY_NAMES[i]}, ${d.toLocaleDateString()}${isToday ? ' (Today)' : ''}`);

      const nameSpan = document.createElement('span');
      nameSpan.className = 'day-pill-name';
      nameSpan.textContent = isToday ? 'Today' : DAY_NAMES[i];
      pill.appendChild(nameSpan);

      const numSpan = document.createElement('span');
      numSpan.className = 'day-pill-number';
      numSpan.textContent = String(d.getDate());
      pill.appendChild(numSpan);

      if (hasMeetings) {
        const dot = document.createElement('span');
        dot.className = 'day-pill-dot';
        dot.setAttribute('title', 'Has meetings');
        pill.appendChild(dot);
      }

      pill.addEventListener('click', () => {
        this.selectedDate = d;
        this.render();
        if (this.onDaySelected) {
          this.onDaySelected(new Date(d));
        }
      });

      this.container.appendChild(pill);
    }
  }
}

---
id: daily-planner
name: Daily planner checklist
description: Turns your tasks for the day into a time-blocked schedule and checklist; can add an event to your calendar.
triggers: [plan my day, daily plan, today's plan, schedule my day, to-do, todo]
tools: [get_datetime, memory_read, render_ui, create_calendar_event]
output: [heading, steps, checklist, callout]
max_steps: 5
---
1. Call get_datetime to know today's date and time. Optionally memory_read for the user's routine.
2. Order the user's tasks: fixed-time items first, then the most important work in the morning, breaks every 90 minutes.
3. Finish with render_ui: a heading with the date, a steps block (title = time slot, detail = task),
   a checklist of all tasks, and a callout with the top priority.
4. Only call create_calendar_event if the user explicitly asked to add something to the calendar.

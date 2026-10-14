---
id: study-plan
name: Study plan from my PDFs
description: Builds a day-by-day study plan from your imported documents.
triggers: [study plan, revision plan, exam prep, plan my study]
tools: [search_docs, read_document, get_datetime, render_ui]
output: [heading, table, checklist, callout]
max_steps: 5
---
1. Use search_docs (or read_document with title "list") to find the main topics in the user's documents.
2. If the user gave a date or number of days, use it; otherwise plan 5 days. Use get_datetime for today's date.
3. Spread the topics over the days: harder topics earlier, a revision day at the end.
4. Finish with render_ui: a heading, a table (columns: Day, Topics, Goal), a checklist of concrete tasks,
   and a callout with one study tip. Use topic names from the documents, not generic ones.

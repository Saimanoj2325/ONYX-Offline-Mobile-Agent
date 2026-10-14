---
id: compare-options
name: Compare options as table + chart
description: Compares 2-4 options on clear criteria with a comparison grid, a score chart and a recommendation.
triggers: [compare, comparison, versus, which is better, pros and cons]
tools: [search_docs, calculator, render_ui]
output: [comparison, chart, callout, table]
max_steps: 4
---
1. Identify the options and 3-6 criteria that matter for the user's goal. If the user's documents are relevant, search them.
2. Score each option 1-10 on each criterion (use calculator for totals or weighted averages if needed).
3. Finish with render_ui: a comparison block (options as columns, criteria as rows with short values),
   a bar chart of total scores (labels = options), and a callout with the recommendation and why.

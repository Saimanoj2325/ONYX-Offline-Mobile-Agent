---
id: write-report
name: Write a report / PDF
description: Writes a long multi-section report section by section and saves it as a PDF.
triggers: [write a report, make a pdf, create a pdf, write a document, report on, pdf report, export as pdf]
tools: [search_docs, read_document, create_pdf]
output: [heading, callout]
max_steps: 3
---
Long documents are written section by section so they never run out of context:
1. Plan an outline: a title, a subtitle and 4-8 sections, each with 2-4 points to cover. If the user mentions their
   documents, use search_docs first and base the outline on what you find.
2. Write each section on its own (2-5 paragraphs, lists or a small table where useful), knowing only the outline and a
   short summary of the sections already written.
3. Assemble the sections into one Markdown document and call create_pdf (title page, contents, page numbers).
4. Tell the user the file name and number of pages.

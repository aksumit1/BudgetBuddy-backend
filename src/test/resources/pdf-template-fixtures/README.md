# PDF template fixtures

One directory per institution slug. Each fixture is a pair:

- `<slug>.txt` — raw extracted text that the parser sees
- `<slug>.expected.json` — the exact transactions that must come out

```
pdf-template-fixtures/
├── chase/
│   ├── checking-basic.txt
│   └── checking-basic.expected.json
├── hdfc-bank/
│   ├── savings-upi.txt
│   └── savings-upi.expected.json
└── ...
```

## Running

```
mvn test -Dtest=PdfTemplateFixtureTest
```

Every fixture produces a dynamic test. A failure names the institution +
fixture so you know exactly what regressed.

## Expected JSON shape

```json
{
  "institution": "Chase",
  "note": "synthetic — pending real anonymised sample",
  "expected": [
    { "date": "2024-03-15", "description": "AMZN MKTP", "amount": "34.99" }
  ]
}
```

- `institution` must match the institution name in the template YAML so
  the registry's `orderedFor` preference kicks in.
- `note` is freeform — use it to flag synthetic fixtures vs. real samples.
- `expected[]` is ordered — rows must appear in the same order they appear
  in the text fixture.

## Date format

Every `expected.date` is **ISO `yyyy-MM-dd`**, regardless of the source
format the template is parsing. The template's `dateFormat` does the
conversion; this field matches the canonicalised output.

## Writing a new fixture

1. Get a real statement PDF from the target bank (anonymise card numbers
   and account holder names before committing).
2. Extract text with `pdftotext`:
   ```
   pdftotext -layout my-statement.pdf my-statement.txt
   ```
3. Trim to 10-30 representative lines — don't commit the whole statement.
4. Write the `.expected.json` with the exact rows the template should
   produce.
5. Run the harness. Iterate template regex until it passes.
6. Once green, promote the template's `status:` field from `UNVERIFIED`
   to `VALIDATED`.

## Synthetic vs real

Fixtures in this directory are clearly labelled in their `note` field.
**Synthetic fixtures lock in our guess at each bank's layout**; they're
a starting point, not ground truth. When a real statement is obtained,
the synthetic fixture is deleted and replaced.

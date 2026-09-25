## Purpose

Guarantee that a bilingual conversation card renders complete, language-correct
text inside its dialogue frame, so a reply never loses characters or splits a
translation away from its original.

## ADDED Requirements

### Requirement: Pagination counts flattened pairs

The system SHALL bound each rendered page by the configured rendering character
limit and SHALL count pairs after bracket-group flattening, so a reply that packs
several groups onto one physical line is paginated by pair rather than by line.
Each page SHALL retain the canonical translation-first, original-second structure.

#### Scenario: A single pair exceeds one card

- **WHEN** a bilingual pair contains more visible characters than the configured
  page limit
- **THEN** the system produces more than one renderable page without placing the
  complete oversized pair on any single page

#### Scenario: A pair fits one card

- **WHEN** a bilingual pair does not exceed the configured page limit
- **THEN** the system produces exactly one page for that pair

#### Scenario: Packed pairs are counted individually

- **WHEN** a reply puts two bracket groups on each of two physical lines
- **THEN** the system reports two pairs and produces two ordered panels

### Requirement: Packed bracket groups pair by language

The system SHALL pair bilingual content by full-width bracket group order, not by
physical line, so a line carrying several groups produces the same pairs as the
canonical one-group-per-line layout.

#### Scenario: Several groups share one physical line

- **WHEN** a reply contains `【中文】【日文】` on one line instead of the canonical
  translation line followed by an original line
- **THEN** the translation and the original are reported as one pair, and no pair
  mixes Chinese and Japanese inside a single string

#### Scenario: Both layouts are equivalent

- **WHEN** the same pairs are formatted once in the canonical layout and once in
  the packed layout
- **THEN** parsing both layouts returns identical pairs

### Requirement: Unbracketed bilingual replies pair by script

The system SHALL accept a reply that carries no full-width brackets when every
non-empty line pairs as a Chinese line without kana followed by a line containing
kana, and SHALL reject any other unbracketed text.

#### Scenario: A Core direct reply without brackets

- **WHEN** a reply is a bare Chinese line followed by a bare Japanese line
- **THEN** the two lines are reported as one translation/original pair

#### Scenario: Ordinary multi-line text without brackets

- **WHEN** a reply is several lines that do not alternate Chinese-without-kana and
  kana-bearing text
- **THEN** the parser reports no pairs and the caller keeps the original text

#### Scenario: A malformed bracketed reply

- **WHEN** a reply uses brackets but the group count is odd or a line carries prose
- **THEN** the parser reports no pairs and does not fall back to script pairing

### Requirement: Non-bilingual text is never misread

The system SHALL return no pairs when a line contains prose outside bracket
groups, an empty group, or an odd number of groups.

#### Scenario: Prose around bracket lines

- **WHEN** a reply mixes ordinary prose with one or more bracket lines
- **THEN** the parser reports no bilingual pairs and the caller keeps the original
  text unchanged

### Requirement: Bilingual text fits the dialogue frame

The system SHALL reduce the bilingual font size until the wrapped pair block fits
the available text-box height, and SHALL NOT draw the block past the frame.

#### Scenario: A pair block is taller than the text box

- **WHEN** the pair block measured at the configured font size exceeds the text-box
  height
- **THEN** the system reduces the font size until the measured block fits, and
  never below the configured minimum size

#### Scenario: A pair block already fits

- **WHEN** the pair block measured at the configured font size fits the text box
- **THEN** the configured font size is used unchanged

### Requirement: Font collections are probed before falling back

The system SHALL, when the configured font path is a font collection (`.ttc`),
attempt the collection's faces in order instead of relying only on the first face,
and SHALL fall back to the body font when no face can be opened.

#### Scenario: A collection is configured

- **WHEN** the configured font path is a `.ttc` collection
- **THEN** the system attempts its faces in order and only falls back to another
  font when none of them can be opened

#### Scenario: An earlier face cannot cover the line

- **WHEN** a face of the collection opens but cannot render the drawn line
- **THEN** the renderer still draws with the selected face; language-correct
  pairing is what keeps each line inside a font that covers it

### Requirement: A Meguri turn is decorated in isolation

The system SHALL render a Meguri turn from a decorator that runs before other
plugins' decorators and SHALL stop event propagation afterwards, whether or not
rendering succeeded, while leaving non-Meguri turns to the other decorators.

#### Scenario: A Meguri turn is decorated

- **WHEN** the event carries a Meguri render payload
- **THEN** the Meguri decorator renders the reply and then stops the event

#### Scenario: Rendering the Meguri turn fails

- **WHEN** the Meguri decorator raises while rendering
- **THEN** the event is still stopped before the exception propagates

#### Scenario: A turn is not a Meguri turn

- **WHEN** the event carries no Meguri render payload
- **THEN** the Meguri decorator does nothing, the event is not stopped, and the
  fallback decorator handles the turn

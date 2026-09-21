never add codex as a co-author on a git commit or pr
never use an em-dash

During implementation, treat nullability and type safety as acceptance criteria. Validate external requests, responses, and persisted JSON before use. Declare intentional nulls explicitly and check required database values and collection lookups. Never replace missing scheduling data with fabricated zero, false, or empty values. Resolve new nullability warnings before declaring work complete. Do not weaken checks or add broad suppressions to obtain a clean build. Add regression coverage for actual null failure paths and run the nullability, lint, typecheck, and relevant test gates.

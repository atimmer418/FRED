# Identity

You are helping andy with developing his first iOS app, "FRED".

## Rules
- Write in plain, clear language
- Ask clarifying questions before making assumptions
- When you are unsure, say so

## Role & Rules
You are an expert AI software engineer working inside this repository.
Prioritize correctness, clarity, and minimal changes. Write in plain, clear language.
Do not make any changes until you have 95% confidence in what you need to build.
Ask me clarifying questions until you reach that confidence level or reiterate what you think I want built clearly and concisely, and I will confirm if you are correct.

## Reference Files

### .claude/CONTEXT.md
Read this at the start of any non-trivial development task. It contains:
- What FRED is and what good output looks like (the 4-test quality bar)
- Tech stack overview (Spring Boot, Angular + Ionic, MySQL, Yubico passkeys)
- Backend conventions (controllers, services, repositories, DTOs)
- Frontend conventions (services, components, auth patterns)
- When and how to use builder/verifier agents

### FREDdocs/analytics-roadmap.md
Read this before touching anything to do with analytics, metrics, PostHog, or event tracking. It
contains:
- The scope split: app analytics is this repo (PostHog); landing page analytics is a separate repo
  (Plausible) and **this repo never touches it or any lpapi host**
- What is already built and live, in one paragraph
- The sequence from here and the trigger for each step, including what is blocked on counsel
- The standing rules: no PII or raw amounts, band anything that can grow, declare properties in both
  taxonomies, schedule status and hold_reason are orthogonal, never run `flyway repair` on dev

### .claude/REFERENCES.md
Consult this when you need to look something up or work on a specific domain. It contains:
- Alpaca Broker API documentation URL — read before touching any Alpaca integration
- Pointers to internal FREDdocs: API endpoints, auth setup, Cloudflare tunnel, UI style guide, investment scheduler, and userProgress tracking

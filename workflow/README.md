# CI Workflow files

This folder contains the GitHub Actions workflow file(s) for this project.

> ⚠️ **Important:** The integration token used by the AI agent does not have
> `workflows` scope, so the file was committed here instead of directly to
> `.github/workflows/`. To activate CI:
>
> 1. Move (or copy) `build.yml` from this folder into `.github/workflows/`.
> 2. Commit and push to `main`.
> 3. The workflow will then build a signed release APK on every push, attach
>    it as an artifact, and create a draft GitHub Release.
>
> The contents of this file are 100% ready to use as-is — no edits required.

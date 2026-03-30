---
description: This rule ensures the PluginUpdater always checks for the 'latest'
  tag first, as requested by the user. If the tag doesn't exist, it picks the
  newest release from the list (which includes pre-releases and drafts).
alwaysApply: true
---

Always try to fetch the 'latest' tag first for all plugins, regardless of asset pattern. If the 'latest' tag does not exist (404), fall back to the newest release from the releases list (including pre-releases and drafts). This ensures that repositories using a rolling 'latest' tag are always updated correctly.
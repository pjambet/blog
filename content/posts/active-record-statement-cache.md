---
title: "ActiveRecord caches statement for find_by queries"
date: 2021-12-03
lastmod: 2021-12-03
tags : [ "dev", "rails", "activerecord", "sql" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: false
description: "ActiveRecord caches ..."
---

tl;dr; When using `find_by` queries, and `find`, ActiveRecord caches the
generated queries based on the given conditions. This is different from
prepared statement, which are cached at the DB level

## What exactly does ActiveRecord cache, and when?

...

## Why does it actually matter?

...

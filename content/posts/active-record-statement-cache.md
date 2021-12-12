---
title: "ActiveRecord caches generated queries for find_by queries"
date: 2021-12-11
lastmod: 2021-12-11
tags : [ "dev", "rails", "activerecord", "sql" ]
categories : [ "dev" ]
layout: post
highlight: false
draft: false
summary: "Active Record caches the generated queries, when using `find_by` and `find`"
---

__tl;dr; Active Record caches the generated queries, when using `find_by` and `find`. This is a different type of cache, unrelated to [`QueryCache`][query-cache] and prepared statements.__


## What exactly does ActiveRecord cache, and when?

When you call `Post.find(1)` or `Post.find_by(id: 1)`, ActiveRecord will cache the result of [`build_arel`][build-arel] in a [`StatementCache`][statement-cache] instance. The `build_arel` method is the one that generates the actual underlying query, in this case, `SELECT * FROM posts WHERE id = ?`.

## Why does it actually matter?

This is a small internal optimization that should be invisible in most cases. The generated queries are parameterized, so once they are cached, they can be reused regardless of the arguments. It should speed things up and all is well.

I had an issue when hooking into Active Record's internals, using a custom module included into `ActiveRecord::Relation`:

```ruby
module M
  def build_arel(aliases=nil)
    super.tap do |arel|
      # look into the generated query
    end
  end
end

ActiveSupport.on_load :active_record do
  ActiveRecord::Relation.include M
end
```

I was expecting my custom code inside `#tap` to always be executed, but since Active Record caches the result of `build_arel`, the first call would go through `build_arel` and subsequent calls to `find` would use the cached result and never hit the `#tap` method.

A quick workaround I found was to add `arel.model.intialize_find_by_cache if Rails.env.test?` at the end of my `#tap` block, to make sure the cache would get reset and therefore nothing would actually end up cached. In my case, this was a test only thing, and resetting the cache in this context was not an issue.

## Final words

I used to be scared of digging into Rails' code, afraid it would be "too complicated". But it's actually not that bad! I fully recommend checking it out, if you don't know where to start, let me recommend the [`find` method][find-method].

### Links:

- https://flexport.engineering/avoiding-activerecord-preparedstatementcacheexpired-errors-4499a4f961cf
- https://www.honeybadger.io/blog/rails-activerecord-caching/

[query-cache]:https://guides.rubyonrails.org/caching_with_rails.html#sql-caching
[build-arel]:https://github.com/rails/rails/blob/v7.0.0.rc1/activerecord/lib/active_record/relation/query_methods.rb#L1321-L1347
[statement-cache]:https://github.com/rails/rails/blob/v7.0.0.rc1/activerecord/lib/active_record/statement_cache.rb
[find-method]:https://github.com/rails/rails/blob/v7.0.0.rc1/activerecord/lib/active_record/core.rb#L268-L285

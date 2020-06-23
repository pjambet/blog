---
title: "Mysql Utf8"
date: 2013-08-17T15:07:47-04:00
lastmod: 2020-06-17T15:07:47-04:00
tags : [ "dev", "sql", "mysql" ]
categories : [ "dev" ]
summary: "The limitation of MySQL's default utf8 encoding and how to use a better one"
layout: post
highlight: false
draft: false
description: "MySQL UTF8 support for emojis"
---

##### Originally published at [https://pjambet.github.io/](https://pjambet.github.io/blog/emojis-and-mysql)

**tl;dr; MySQL regular UTF8 encoding sucks, you should use UTF8mb4 (or even
better, Postgres !)**

# Intro

{{% details "This is the details title (click to expand)" %}}
This is the content (hidden until clicked).
{{% /details %}}

In the past few months I had to struggle with MySQL and its crappy UTF8 support,
so I thought I would sum up my findings here.

If you've seen stuff like

```
Mysql::Error: Specified key was too long; max key length is 767 bytes
```

or

```
Mysql2::Error: Incorrect string value: '\xF0\x9F\x8F\xA0' for column 'body' at row 1 ...
```

then maybe this article can help you.

## Technical details !

Basically, in order to store the whole range of UTF8 characters, each chararacter
has to be 4 bytes wide. But MySQL, with its default UTF8 encoding will only
allocate 3 bytes per character.
This becomes a problem when users try to insert character such as emojis in your
database.
That might sound like an edge case, but if your app is an API where users can
post content through their phones, then it's really likely some people will add
emojis.

If you want a comprehensive guide for migrating your mysql database from utf8 to
utf8mb4, [this article](http://mathiasbynens.be/notes/mysql-utf8mb4) contains
everything you need.

## How to use it in Rails and Django

Using utf8mb4 is quite straight forward

### Django example :

in your new project :

```
$> django-admin.py startproject test
```

Simply add the `charset` option in the `DATABASES` setting :

*settings.py*

```
DATABASES = {
    'default': {
        'ENGINE': 'django.db.backends.mysql',
        'NAME': 'test',
        'USER': 'test_user'
        'OPTIONS': {'charset': 'utf8mb4'},
        'PASSWORD': '',
        'HOST': '',
        'PORT': '',
    }
}
```

And you're good to go !

#### Caveat

There is still a problem with that configuration. Now the maximum length that
MySQL can index is `191` instead of `255`.
That won't be a problem if you apply this new limit on your models, but some
libraries, such as celery try to index some column with a 255 max length.

So if you have `djcelery` in your `INSTALLED_APPS`, running your migrations
(`python manage.py migrate`) will fail with a nice :

```
django.db.utils.DatabaseError: (1071, 'Specified key was too long; max key length is 767 bytes')
```

The only solution I found so far is to only run migrations on an app per app
basis.

```
python manage.py migrate myapp
python manage.py migrate anotherapp
```

and for the app that fails, such as `djcelery`, first run

`python manage.py sqall djcelery`

Which should give you an output similar to :

```
BEGIN;
CREATE TABLE `celery_taskmeta` (
    `id` integer AUTO_INCREMENT NOT NULL PRIMARY KEY,
    `task_id` varchar(255) NOT NULL UNIQUE,
    `status` varchar(50) NOT NULL,
    `result` longtext,
    `date_done` datetime NOT NULL,
    `traceback` longtext,
    `hidden` bool NOT NULL,
    `meta` longtext
)
;

# other CREATE TABLES ...

CREATE INDEX `djcelery_taskstate_c91f1bf` ON `djcelery_taskstate` (`hidden`);
COMMIT;
```    

Then copy this in your favorite editor (vim, of course !), and specify the
`CHARACTER SET` as `utf8` for each table. This should not cause any issues, as
all the data that is gonna be inserted in those tables is controlled by celery
itself, and there should not be any emojis are other 4 bytes wide characters.

Then your new file should look like :

```
BEGIN;
CREATE TABLE `celery_taskmeta` (
    `id` integer AUTO_INCREMENT NOT NULL PRIMARY KEY,
    `task_id` varchar(255) NOT NULL UNIQUE,
    `status` varchar(50) NOT NULL,
    `result` longtext,
    `date_done` datetime NOT NULL,
    `traceback` longtext,
    `hidden` bool NOT NULL,
    `meta` longtext
) CHARACTER SET=utf8 # Note the CHARACTER SET explictly set
;

# ... and the rest
```

PS : I've opened [an issue on
github](https://github.com/celery/django-celery/issues/259), and I am still
waiting for more information
about this problem.

### Rails example :

Note that utf8mb4 support in rails is [really
recent](https://github.com/rails/rails/issues/9855), and is only supported since
rails 4.0.0

in your new rails project :

```
$> rails new test -d mysql
```

*database.yml*

```
# MySQL.  Versions 4.1 and 5.0 are recommended.
#
# Install the MYSQL driver
#   gem install mysql2
#
# Ensure the MySQL gem is defined in your Gemfile
#   gem 'mysql2'
#
# And be sure to use new-style password hashing:
#   http://dev.mysql.com/doc/refman/5.0/en/old-client.html
development:
    adapter: mysql2
    encoding: utf8mb4
    database: test_development
    pool: 5
    username: root
    password:
    socket: /tmp/mysql.sock

# Warning: The database defined as "test" will be erased and
# re-generated from your development database when you run "rake".
# Do not set this db to the same as development or production.
test:
    adapter: mysql2
    encoding: utf8mb4
    database: test_test
    pool: 5
    username: root
    password:
    socket: /tmp/mysql.sock

production:
    adapter: mysql2
    encoding: utf8mb4
    database: test_production
    pool: 5
    username: root
    password:
    socket: /tmp/mysql.sock
```

## Use postgresql if you can !

This won't really help you if you're stuck with MySQL. In my case, I had to use
AWS and had to use MySQL. Even if it's possible to use postgresql on AWS, using
RDS has many advantages, such as the ease of setup and configuration.

So if you have the choice, use postgresql ! I haven't seen any advantages of
MySQL over postgresql.

Postgres UTF8 encoding works like a charm (and it should be the same for every
RDBMS), that means no encoding, collation or index length headaches. It just
works !

And of course if you start using posgresql, you'll discover a bunch of amazing
features such as [array
columns](http://www.postgresql.org/docs/9.1/static/arrays.html),
[hstore](http://www.postgresql.org/docs/9.1/static/hstore.html) and many other
really awesome stuff !
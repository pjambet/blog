---
title: "TIL CLI"
date: 2020-06-17
lastmod: 2020-06-17T15:16:46-04:00
tags : [ "ruby", "cli", "dev" ]
categories : [ "dev" ]
layout: post
highlight: false
summary: "A small cli available as a ruby gem to maintain a repo of TILs"
---

**tl;dr; `til` is a wrapper around a few other tools to simplify the management of a TIL repo, such as
<https://github.com/pjambet/til>. I recently published it as a [ruby gem][1].**

The code can be [found on GitHub][2].

## Intro

At first glance this small CLI might not seem like it does much, and it really doesn't, but while building it I ended
learning quite a few things, three to be exact:

- How to use an external command line tool, such as `fzf`, and feed it input, similar to using a unix pipe, like `echo
  "a\nb\nc" | fzf`, as well as reading the output of the command, but programmatically, in Ruby.
- How to implement a flow similar to what happens when you type `git commit` without the `-m/--message` option and it
  prompts you with an editor, `vim` by default.
- How to use the Gitub API to create a new commit, without using the `git` cli.

Before jumping in, here's a summary of what `til` actually does:

- It first loads the list of all existing categories in your TIL repo, and then uses `fzf` to prompt you to pick the
  category for your new TIL. You can also choose to add a new category.
- Once you picked a category, it uses your default editor, as configured through the `$VISUAL` or `$EDITOR` environment
  variables, or `vi` if none of those are defined. You can then type what you actually learned today.
- After saving and closing the text editor, `til` will grab the content of the file, and commit it to the configured
  GitHub repo
- It also takes care of maintaining the `README.md` file so that it contains a nicely organized index of all your
  TILs. It keeps a list of all the categories at the top, and includes a link to each TIL below, grouped by category


### Using fzf

I don't actually use it that much, but I love how [`fzf`][3] improves CLI interactions. I thought it would be a great
addition for the workflow I wanted with `til`. Most of the time I will be reusing existing categories, and as someone
who makes a lot of typos, I always look for ways to avoid having to type anything.

The main way that `fzf` gets its input in from [STDIN][4]. You can test that from the command line: `echo "a\nb\nc" |
fzf` will load `fzf` with three items `a`, `b` & `c`.

I was able to get the list of folders using the [GitHub API][6] [ruby library, Octokit][5]. Each folder is a category,
and I needed to feed that to `fzf`, through STDIN, in Ruby.

It's worth mentioning that I could have used the [backtick][7] approach, but that was a tiny bit too much magic for me
and also, there are few blog posts out there that recommend against using it, and favor the `system` method. In this
case we're not really dealing with user input, so it doesn't matter that much from a security standpoint.

The [`Process.spawn`][8] method accepts a bunch of options, there are a lot, but these are the ones that are
interesting to us here:

```
:in     : the file descriptor 0 which is the standard input
:out    : the file descriptor 1 which is the standard output
```

So, we can create a [`pipe`][12] with [`IO.pipe`][13] and pass the reader as the `:in` argument to `spawn`, so that we
can write with the writer from the main process and the process on the other end, the one created by `spawn` will
receive it.

_Note: it is important to close the `writer` in the initial process, if you don't, `fzf` still thinks that there
might be more to read, and it shows it by displaying a spinner in the bottom left corner of the terminal._

We can use the same approach to get the content that `fzf` will output. Once a selection is made, `fzf` writes it to
[STDOUT][11]. So we create another pipe, and give the writer as the `:out` option, the process started by `spawn` will
be able to write to it and we can then use the other end of the pipe to read from it and get the selection from the
user in the main process.

This is what `til` does, as you can see [on GitHub][10].

A quick look at [the C code][9] defining the backtick function shows that it uses the `pipe` syscall, so we're
essentially reimplementing something fairly similar to what ruby does for us with `` `echo 'a' | fzf` ``.

_Note (another one): As I was writing this post, I realized that Ruby has another method related to spawning new
processes and dealing with STDIN and STDOUT: [`IO.popen`][17]. I haven't looked too much into it yet, but it looks like
it could simplify my code a little bit. That being said, the overall approach described above is still valid._

## Using an external editor

For years I used `git` from the cli and relied on its commit workflow without really wondering how it actually made that
happen. In case you're not familiar with it, from the CLI, when you commit with `git commit` you essentially have two
options, you either provide the commit message inline, through the `-m/--message` option, or you leave this option blank
and `git` opens an editor for you, by default `vim`.

What is actually really cool with this is that you don't have to use `vim`, you could use pretty much any other editors,
that being said, you probably want to pick one that is quick to start so you don't have to wait just to type a commit
message. It's a little bit trickier with editors using a dedicated window such as vscode or macvim. [GitHub's
documentation][14] has a page explaing how you can use the most common visual editors as git editors.

So, as a git cli enthusiast, I wanted to replicate the same worflow: you picked a category, now let's write the
content, in the editor you like using, so you can format things the way you want.

It turns out that it's apparently "the right approach" to first look at the `VISUAL` environment variable and then at
`EDITOR` as explained [here][15] and [there][16].

Reimplementing the git commit workflow turned out to be very little work, you first create a file, Ruby makes that easy
with the [`Tempfile`][18] class, we then use either `system` or `spawn` with the value in `$EDITOR` or `$VISUAL` (we
default to `vi`, just in case, so we have _something_).

The main difference between `spawn` and `system` here is how they return, `system` does not return until the process is
over, whereas `spawn` returns a pid. Since we basically want to wait for the user to close the editor, `system` is
easier, with `spawn` we would have had to use `waitpid` to wait.

Once the child process is done, we can read the content of the file, and VOILA! We have the content, formatted by the
user, in their favorite editor (unless they ended up in `vi` and couldn't figure out how to exit).

You can see that `til` [does exactly what I just described][19]

### Creating a commit using the GitHub API

And now, the final piece of the puzzle, we have a category and a string representing a new TIL, it's time to create a
new commit on GitHub. The GitHub API must have an easy way to do this right?

Well?

You _can_ do it! But is it easy? I'll let you answer on your own.

The new commit needs to contain two changes, the new file we want to create, but also, and this is really one of the
reasons why I wanted to create this tool in the first place, the updates to the README file, to keep the table of
content and the links up to the date with the new TIL.

This [blog post][20] was really helpful but the fact that it didn't include any code examples means that I spent a few
hours (😭) trying to get things workings, here's a summary of what I'm doing to create a new commit, I hope you're
ready:

- [Get the `ref` of the latest commit on master][21]
- [Get the commit object, with the sha obtained in the previous step][22]
- [Get the tree object, based on the latest commit obtained in the previous step][23]
- [Get the readme content][24]
- [Create a blob for the new file][25] - Interesting to note that a blob does not have a path, we only specify the path
  when adding blobs to a tree
- [Get all blobs that are in the current tree, except the README][26] - This is necessary because we don't want the new
  tree to remove any files, so our new tree needs to contain all the old files, plus the new file, plus the updated
  readme, and we definitely don't want the old README
- [Figure out what the content of the README should be][27] - We add a new category at the top if necessary and then we
  add a link to the new TIL at the bottom, keeping the categories sorted alphabetically, and the entries sorted, oldest
  at the top, newest at the bottom. The code is [U (pause) GLY][28], but it works!
- [Create a new blob, for the updated readme][33]
- [Add the new blob (at the correct path) and the blob for the updated readme to the blobs list][29]
- [Create a new tree with all blobs, all the old one, unchanged plus the new file and the updated readme][30]
- ...
- [CREATE A NEW COMMIT, FINALLY][31]
- [You thought we were done? Nope, we have to update master to point to the new commit, and now, we're done][32]


You can find documentation about the API endpoints I'm using on the following pages:

- [The HTTP API Documentation](https://developer.github.com/v3/git/commits/)
- [The octokit gem Documentation](http://octokit.github.io/octokit.rb/Octokit/Client/Commits.html)

## See it in action

![Example image](/gifs/til.gif)

## Conclusion

The current version (0.0.4) is very very basic, but it gets the job done, and I've been using it for a few days
already. I have a few thoughts about what I would like to do next:

- A "real" cli, probably written in go, so that it's easier to distribute, with `brew` for instance. There doesn't seem
  to be any formulae/casks named `til`, so I should hurry up!
- A chrome/firefox extension, so you can do the same without leaving your browser
- Improve the code (if you've looked at it, it's ... far from great, really far)
- Show a terminal spinner at the end when creating the commit, since it can take up to a few seconds. I recently learned
  how to use terminal escape sequences to do this! Read more on my [TIL repo][34] (see what I did there?!). But there
  are also at least two gems that do that for you, [here][35] and [there][36].

Questions? Comments? Hit me up on [Twitter](https://twitter.com/pierre_jambet)!

## Existing gems

There are a few similar gems, both in names and features available on ruby gems, I checked all of them before publishing
mine:

- [til_cli](https://rubygems.org/gems/til_cli), there were two issues with this one:
  - It does not seem to work with latest ruby 2.7.1 because of json 1.8.1 not compiling
  - It runs git commands locally, it basically wraps `git` calls, which is great, but means that the tool is not really
    a standalone tool, I wanted something I could run from anywhere, and that would work on its own.

- [todayilearned](https://rubygems.org/gems/todayilearned): This is an interesting gem, but it works locally with
  sqlite, and while this is great, this is not what I wanted

- [til](https://rubygems.org/gems/til): This seems to the same code as the one above, they both point to this GH repo:
  https://github.com/ip2k/todayilearned

- [til_](https://rubygems.org/gems/til_): This looks like an empty gem, there's no "real" code in there as of today.

[1]: https://rubygems.org/gems/til-rb
[2]: https://github.com/pjambet/til-rb
[3]: https://github.com/junegunn/fzf
[4]: https://en.wikipedia.org/wiki/Standard_streams#Standard_input_(stdin)
[5]: http://octokit.github.io/octokit.rb/Octokit/Client/Contents.html#contents-instance_method
[6]: https://developer.github.com/v3/repos/contents/#get-repository-content
[7]: https://ruby-doc.org/core-2.7.1/Kernel.html#method-i-60
[8]: http://ruby-doc.org/core-2.7.1/Kernel.html#method-i-spawn
[9]: https://github.com/ruby/ruby/blob/v2_7_1/io.c#L9057-L9076
[10]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L94
[11]: https://en.wikipedia.org/wiki/Standard_streams#Standard_output_(stdout)
[12]: https://man7.org/linux/man-pages/man2/pipe.2.html
[13]: http://ruby-doc.org/core-2.7.1/IO.html#method-c-pipe
[14]: https://help.github.com/en/github/using-git/associating-text-editors-with-git#using-sublime-text-as-your-editor-1
[15]: https://thoughtbot.com/blog/visual-ize-the-future
[16]: https://unix.stackexchange.com/questions/4859/visual-vs-editor-what-s-the-difference
[17]: http://ruby-doc.org/core-2.7.1/IO.html#method-c-popen
[18]: http://ruby-doc.org/stdlib-2.7.1/libdoc/tempfile/rdoc/Tempfile.html
[19]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L115-L132
[20]: http://www.levibotelho.com/development/commit-a-file-with-the-github-api/
[21]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L143
[22]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L144
[23]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L145
[24]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L146
[25]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L149
[26]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L150
[27]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L156
[28]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/readme_updater.rb
[29]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L158-L160
[30]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L162
[31]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L163
[32]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L164
[33]: https://github.com/pjambet/til-rb/blob/cd92d528d24a24dc5ca30086c5bdf728f8a9068f/lib/til/core.rb#L157
[34]: https://github.com/pjambet/til/blob/master/unix/2020-06-17_move-cursor-when-priting-to-terminal.md
[35]: https://github.com/piotrmurach/tty-spinner
[36]: https://github.com/janlelis/whirly

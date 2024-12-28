---
title: "A basic terminal text editor, in Ruby"
date: 2024-12-27T00:00:00-05:00
lastmod: 2024-12-27T00:00:00-05:00
tags : [ "dev", "ruby", "terminal" ]
categories : [ "dev" ]
summary: "A basic terminal text editor, in Ruby, inspired by Antirez's kilo text editor, written in C."
layout: post
highlight: false
image: /images/ruby-editor.jpg
---

Antirez wrote a small terminal text editor in C, the source is available [on GitHub][kilo-github]. As someone who doesn't know much C, I found it easy to read, and I learned a lot.

In this blog post, I'll walk through the steps to creating a basic text editor where we can type ASCII characters move the cursor in all four directions, and close the editor. It will be written in Ruby, as a more or less direct translation from kilo.

Kilo does way more, such as _actually_ saving files, and everything you'd want from a text editor. In future posts I'll try to keep adding features to the ruby version.

## Terminal modes: cooked vs raw

When you open a terminal application, such as Terminal.app or iTerm2 on macOS, by default you are in what is known as "cooked mode". Put simply in "cooked mode" there are a good amount of processing steps that are performed between what the user does and what the underlying application receives.

We're building a text editor, so we want full control of the user input, as well as the terminal window, which is what we get in raw mode.

Let's look at a small example to illustrate the difference:

In a new ruby file, here named `cooked.rb`, let's add the following content:

```ruby
loop do
  content = gets
  print "You typed: #{content}"
end
```

If we run this in a terminal with `ruby cooked.rb`, we can start typing, what we type gets displayed, and when we press enter, the `gets` method returns, and the `You typed: ...` lines is printed. If you press Ctrl-C, the program stops.

Now let's change it to the following, which uses the built-in ruby [`io/console` gem][io-console-gem], let's put the following content in a file named `raw.rb`:

```ruby
require "io/console"

IO.console.raw do
  loop do
    content = STDIN.readpartial(1)
    exit(0) if content == 'Q'
  end
end
```

And run it with `ruby raw.rb`.

Note that we added the line to exit the program if the input is the capital letter `Q` because signals are not caught in raw mode, Ctrl-C will not close the program.

Aside from stopping when you type `Q`, you'll notice that nothing gets printed, that's because in raw mode, you're in full control, you have to explicitly print characters to the screen, which we can do with the `write` method on `IO` objects, here we'll print to `STDOUT`:

```ruby
require "io/console"

IO.console.raw do
  loop do
    content = STDIN.readpartial(1)
    STDOUT.write(content)
    exit(0) if content == 'Q'
  end
end
```

Now characters are shown in the UI, but you may have noticed that some features are missing, or straight up not working. For instance, hitting backspace does not erase characters, pressing enter moves the cursor back to the beginning of the line. Overall it is severely limited.

In order to make the editor more useful, we first need to understand [ANSI escape codes][ansi-escape-codes]

## VT 100 codes

Before we dive in, let's change our `raw.rb` file, to be able to print additional information in order to get more information about the information the program receives from various user inputs.
We will use `STDERR`, which is constant that already exists, and will make it easy to write additional information to it, we will run the program a little differently to be able to actually see what we print there.

```ruby
require "io/console"

def log_to_stderr(msg)
  return if STDERR.tty?

  STDERR.puts(msg)
end

IO.console.raw do
  loop do
    content = STDIN.readpartial(1)
    log_to_stderr("read: '#{content}'")
    log_to_stderr("read: '#{content.ord}'")
    exit(0) if content == 'Q'
    STDOUT.write(content)
  end
end
```

We use an early `return` if `STDERR.tty?` is `true`, as to not pollute the main terminal in case `STDERR` is not redirected elsewhere. In other words, if we run the program with `ruby raw.rb`, `STDERR` will default to be the same as `STDOUT`, and we'll log additional characters to the main screen, you can try to comment out that the early `return` and see if for yourself, it's messy!

But now, if we redirect `STDERR` to a different file, it'll get printed there, we can do that with `ruby raw.rb 2>stderr.log`, now if you open another terminal window and run `tail -f stderr.log`, you'll see new rows added to that file, looking like `read: '...'`.

For each byte we read, we print two lines to `STDERR`, the actual byte we read, but also the result of the `#ord` method ([docs][ruby-ord-method]). This method will be extremely convenient because the integer it returns will allow us to identify the different key strokes, even non printable characters such as backspace.

If you type ASCII characters, you'll see these characters printed there, everything seems normal.

But if you use the backspace key or the enter key, "weird" thing will happen.

If we look at the content of the `error` file, we can see that if we type the letter `a`, `ord` returns `97`, `b` returns `98`, everything matches the [ASCII table][ascii-table].

When typing `Enter` or `backspace`, printing the character itself looks weird, but `ord` gives us more insight into what we read, `enter` is the value `13` and `backspace` is `127`.

Using the arrow keys, we can see that a single keystroke sends multiple bytes. The left arrow sends `27`, `91` & `67`. Looking at the [ASCII table][ascii-table], we can see that this maps to respectively, the characters `ESC`, `[` & `C`.

This specific sequence is called an [ANSI escape code][ansi-escape-codes]. [This article][burke-libbey-codes] is a great explanation of how these escape codes work. The important part for us is the first character, `27`, can be encoded in Ruby with `"\x1b"`

We'll use the following escape codes to build our editor:

- `\x1b[?25l` hides the cursor
- `\x1b[?25h` shows the cursor
- `\x1b[H` moves the cursor to the beginning of the line
- `\x1b[0K` clears the rest of the line
- `\x1b[39m` sets the default foreground color
- `"\x1b[Y;XH"` places the cursor at coordinates X & Y where X is the 0-index value of the column, and Y is the 0-index value of the row.

## Drawing our editor

Let's now use these escape codes to draw the editor. First we need to know the size of the current window. The io-console gem provides a helper for this, `IO.console.winsize`, it returns a tuple, first the height, then width.

```ruby
require "io/console"

PRINTABLE_ASCII_RANGE = 32..126
HIDE_CURSOR = "\x1b[?25l"
SHOW_CURSOR = "\x1b[?25h"
HOME = "\x1b[H"
CLEAR = "\x1b[0K"
DEFAULT_FOREGROUND_COLOR = "\x1b[39m"

def log_to_stderr(msg)
  return if STDERR.tty? # true when not redirecting to a file, a little janky but works for what I want

  STDERR.puts(msg)
end

def coordinates(x, y)
  "\x1b[#{ y };#{ x }H"
end

def refresh(height, x, y)
  append_buffer = String.new
  append_buffer << HIDE_CURSOR
  append_buffer << HOME

  height.times do |row_index|
    append_buffer << "~#{ CLEAR }\r\n"
  end

  append_buffer.strip!
  append_buffer << HOME
  append_buffer << coordinates(x, y)
  append_buffer << SHOW_CURSOR
  log_to_stderr("'#{ append_buffer }'".inspect)
  log_to_stderr("Cursor postition: x: #{ @x }, y: #{ @y }: #{ @y };#{ @x }H")

  STDOUT.write(append_buffer)
end

IO.console.raw do
  height, _width = IO.console.winsize
  x = 0
  y = 0
  loop do
    refresh(height, x, y)
    content = STDIN.readpartial(1)
    log_to_stderr("read: '#{content}'")
    log_to_stderr("read: '#{content.ord}'")
    exit(0) if content == 'Q'
  end
end
```

## Moving around

```ruby
require "io/console"

class Editor
  HIDE_CURSOR = "\x1b[?25l"
  SHOW_CURSOR = "\x1b[?25h"
  HOME = "\x1b[H"
  CLEAR = "\x1b[0K"
  DEFAULT_FOREGROUND_COLOR = "\x1b[39m"

  ESC = 27
  CTRL = ""
  CTRL_Q = 17

  UP = "A"
  DOWN = "B"
  RIGHT = "C"
  LEFT = "D"

  PRINTABLE_ASCII_RANGE = 32..126

  def initialize(stdin: STDIN, stdout: STDOUT, stderr: STDERR)
    @height, @width = IO.console.winsize
    @in = stdin
    @out = stdout
    @err = stderr
    @text_content = [String.new]
    @x = 1
    @y = 1
  end

  def start
    raw_mode do
      loop do
        refresh
        begin
          char = @in.readpartial(1)
        rescue EOFError
          stderr_log("next, eof error, #{Time.now}")
          next
        end
        stderr_log(char)
        process_keypress(char)
      end
    end
  end

  private

  def refresh
    append_buffer = String.new
    append_buffer << HIDE_CURSOR
    append_buffer << HOME

    @height.times do |row_index|
      if row_index >= @text_content.count
        append_buffer << "~#{ CLEAR }\r\n"
        next
      end

      row = @text_content[row_index] || String.new
      append_buffer << row
      # https://notes.burke.libbey.me/ansi-escape-codes/
      # https://en.wikipedia.org/wiki/ANSI_escape_code
      append_buffer << DEFAULT_FOREGROUND_COLOR
      append_buffer << CLEAR
      append_buffer << "\r\n"
    end
    append_buffer.strip!
    append_buffer << HOME
    append_buffer << coordinates(@x, @y)
    append_buffer << SHOW_CURSOR
    stderr_log("'#{ append_buffer }'".inspect)
    stderr_log("Cursor postition: x: #{ @x }, y: #{ @y }: #{ @y };#{ @x }H")

    @out.write(append_buffer)
  end

  def stderr_log(message)
    return if @err.tty? # true when not redirecting to a file, a little janky but works for what I want

    @err.puts(message)
  end

  def raw_mode(&block)
    # block.call
    if @out.tty?
      IO.console.raw(min: 0, time: 0.1, &block)
    else
      block.call
    end
  end

  def process_keypress(character)
    if character.ord == CTRL_Q
      clear_screen!
      exit(0)
    elsif character.ord == ESC
      second_char = @in.read_nonblock(1, exception: false)
      return if second_char == :wait_readable

      third_char = @in.read_nonblock(1, exception: false)
      return if third_char == :wait_readable

      if second_char == "["
        case third_char
        when UP
          up!
        when DOWN
          down!
        when RIGHT
          if current_row && @x > current_row.length
            if @y <= @text_content.length + 1
              @x = 1
              @y += 1
            end
          elsif current_row
            @x += 1
          end
        when LEFT
          if @x == 1
            if @y > 1
              @y -= 1
              @x = current_row.length + 1
            end
          else
            @x -= 1
          end
        # when HOME then "H" # Home # TODO
        # when END_ then "F" # End # TODO
        end
      end
    elsif PRINTABLE_ASCII_RANGE.cover?(character.ord)
      @text_content << String.new if current_row.nil?
      current_row.insert(@x - 1, character)
      @x += 1
    else
      stderr_log("Ignored char: #{ character.ord }")
    end
  end

  def up!
    @y -= 1 unless @y == 1
    return unless current_row && @x > current_row.length + 1

    @x = current_row.length + 1
  end

  def down!
    @x = 1 if @y == @text_content.length
    @y += 1 unless @y == @text_content.length + 1
    return unless current_row && @x > current_row.length + 1

    @x = current_row.length + 1
  end

  def clear_screen!
    clear = ([HOME] + @height.times.map do
      "#{ CLEAR }\r\n"
    end + [HOME]).join
    stderr_log(clear.inspect)
    @out.write(clear)
  end

  def current_row
    @text_content[@y - 1]
  end

  def coordinates(x, y)
    "\x1b[#{ y };#{ x }H"
  end
end

Editor.new.start
```

## Conclusion

What we build is very simple, and only a subset of what kilo does, in future posts we'll add

[kilo-github]:https://github.com/antirez/kilo
[io-console-gem-ruby]:https://github.com/ruby/ruby/tree/master/ext/io/console
[io-console-gem]:https://github.com/ruby/io-console
[ansi-escape-codes]:https://en.wikipedia.org/wiki/ANSI_escape_code
[burke-libbey-codes]:https://notes.burke.libbey.me/ansi-escape-codes/
[ruby-ord-method]:https://ruby-doc.org/3.3.6/String.html#method-i-oct
[ascii-table]:https://www.asciitable.com/

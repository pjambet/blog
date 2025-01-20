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

Antirez, of Redis fame, wrote a small terminal text editor in C, the source is available [on GitHub][kilo-github]. As someone who doesn't know much C, I found it easy to read, and I learned a lot.

In this blog post, I'll go through the steps of creating a basic text editor where we can type ASCII characters, move the cursor in all four directions, and close the editor. It will be written in Ruby, as a more or less a direct translation from kilo.

Kilo does way more, such as saving files, syntax highlighting, and everything you'd want from a text editor. In future posts I'll try to keep adding features to the ruby version.

## Terminal modes: cooked vs raw

When you open a terminal application, such as Terminal.app or iTerm2 on macOS, by default you are in what is known as "cooked mode". Put simply, in "cooked mode" there are a good amount of processing steps that are performed between what the user does and what the underlying application receives.

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

In order to make the editor more useful, we first need to understand [ANSI escape codes][ansi-escape-codes].

## VT 100 codes

Before we dive in, let's change our `raw.rb` file, to be able to print additional information in order to get more information about the information the program receives from various user inputs.
We will use `STDERR`, which is a constant that already exists. It makes it easy to write additional information in a location of our choosing, we will run the program a little differently to be able to actually see what we print there.

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
TEXT_CONTENT = [String.new]

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
    if row_index >= TEXT_CONTENT.count
      append_buffer << "~#{ CLEAR }\r\n"
      next
    end
    row = TEXT_CONTENT[row_index] || String.new
    append_buffer << row
    append_buffer << DEFAULT_FOREGROUND_COLOR
    append_buffer << CLEAR
    append_buffer << "\r\n"
  end

  append_buffer.strip!
  append_buffer << HOME
  append_buffer << coordinates(x, y)
  append_buffer << SHOW_CURSOR
  log_to_stderr("'#{ append_buffer }'".inspect)
  log_to_stderr("Cursor postition: x: #{ x }, y: #{ y }: #{ y };#{ x }H")

  STDOUT.write(append_buffer)
end

IO.console.raw do
  height, _width = IO.console.winsize
  x = 1
  y = 1
  loop do
    refresh(height, x, y)
    content = STDIN.readpartial(1)
    log_to_stderr("read: '#{content}'")
    log_to_stderr("read: '#{content.ord}'")
    if content == 'Q'
      exit(0)
    else
      current_row = TEXT_CONTENT[y - 1]
      current_row.insert(x - 1, content) # Insert at -1 on an empty string is fine
      x += 1
    end
  end
end
```

We can now type regular characters

TODO: Explain more

## Moving around

The last thing we will add to our editor is the ability to use the four directional arrows, the backspace key and the enter key.

First, we'll wrap the code in a new `Editor`, class. So far we've used constants and a bunch of methods because we started with a small example, but there's now a good amount of state we have to manage such as the current content of the editor and the current coordinates.


```ruby
require "io/console"

class Editor
  PRINTABLE_ASCII_RANGE = 32..126
  HIDE_CURSOR = "\x1b[?25l"
  SHOW_CURSOR = "\x1b[?25h"
  HOME = "\x1b[H"
  CLEAR = "\x1b[0K"
  DEFAULT_FOREGROUND_COLOR = "\x1b[39m"

  def initialize
    @text_content = [String.new]
    @x = 1
    @y = 1
    @height, @width = IO.console.winsize
  end

  def start
    IO.console.raw do
      loop do
        refresh
        content = STDIN.readpartial(1)
        log_to_stderr("read: '#{content}'")
        log_to_stderr("read: '#{content.ord}'")
        if content == 'Q'
          exit(0)
        else
          current_row = @text_content[@y - 1]
          current_row.insert(@x - 1, content) # Insert at -1 on an empty string is fine
          @x += 1
        end
      end
    end
  end

  private

  def log_to_stderr(msg)
    return if STDERR.tty? # true when not redirecting to a file, a little janky but works for what I want

    STDERR.puts(msg)
  end

  def coordinates
    "\x1b[#{ @y };#{ @x }H"
  end

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
      append_buffer << DEFAULT_FOREGROUND_COLOR
      append_buffer << CLEAR
      append_buffer << "\r\n"
    end

    append_buffer.strip!
    append_buffer << HOME
    append_buffer << coordinates
    append_buffer << SHOW_CURSOR
    log_to_stderr("'#{ append_buffer }'".inspect)
    log_to_stderr("Cursor postition: x: #{ @x }, y: #{ @y }: #{ @y };#{ @x }H")

    STDOUT.write(append_buffer)
  end
end

Editor.new.start
```

Let's start with backspace

We will start by introducing a method dedicated to handling characters read from STDIN:

```ruby
def start
  IO.console.raw do
    loop do
      refresh
      content = STDIN.readpartial(1)
      log_to_stderr("read: '#{content}'")
      log_to_stderr("read: '#{content.ord}'")
      process_keypress(content)
    end
  end
end

def process_keypress(content)
  if content == 'Q'
    exit(0)
  else
    current_row = @text_content[@y - 1]
    current_row.insert(@x - 1, content) # Insert at -1 on an empty string is fine
    @x += 1
  end
end
```

Let's now detect whether or not the last key pressed was the backspace key. According to the [ASCII table][ascii-table], the ordinal value for backspace is 127:

```ruby
def process_keypress(content)
  if content == 'Q'
    exit(0)
  elsif content.ord == BACKSPACE
    log_to_stderr("Backspace pressed")
  else
    current_row = @text_content[@y - 1]
    current_row.insert(@x - 1, content) # Insert at -1 on an empty string is fine
    @x += 1
  end
end
```

The behavior we need to implement depends on the content preceding the current cursor position:

- If there's nothing to the left of the cursor, do nothing
- If there's a character, remove it
- If there's nothing on the current line, move the cursor to the end of the previous line. Since we haven't yet implemented multi line handling, we'll add this behavior when adding handling for the enter key

```ruby
BACKSPACE = 127
# ...
def process_keypress(content)
  current_row = @text_content[@y - 1]

  if content == 'Q'
    exit(0)
  elsif content.ord == BACKSPACE
    return if @x == 1 && @y == 1

    if @x == 1
      # TODO
    else
      deletion_index = @x - 2
      current_row.slice!(deletion_index)
      @x -= 1
    end
  else
    current_row.insert(@x - 1, content) # Insert at -1 on an empty string is fine
    @x += 1
  end
end
```

Let's now add handling for four arrow keys.

As we discovered earlier, a single press on an arrow key sends three bytes to the program. For instance the left arrow sends the bytes `27`, `91` & `68`. `27` is a non-printable character, `27` is the byte for the character `[` and `68` the byte for the character `D`

```ruby
OPENING_SQUARE_BRACKET = "["
UP = "A"
DOWN = "B"
RIGHT = "C"
LEFT = "D"
# ...
def process_keypress(content)
  current_row = @text_content[@y - 1]

  if content == 'Q'
    exit(0)
  elsif content.ord == BACKSPACE
    return if @x == 1 && @y == 1

    if @x == 1
      # TODO
    else
      deletion_index = @x - 2
      current_row.slice!(deletion_index)
      @x -= 1
    end
  elsif content.ord == ESC
    second_char = STDIN.read_nonblock(1, exception: false)
    return if second_char == :wait_readable

    third_char = STDIN.read_nonblock(1, exception: false)
    return if third_char == :wait_readable

    if second_char.ord == OPENING_SQUARE_BRACKET.ord
      case third_char
      when UP
        return if current_row.nil?

        @y -= 1 if @y > 1

        current_row = @text_content[@y - 1]
        return if current_row.nil?
        # Keep the cursor at the same column position if the new current line is longer
        return if @x <= current_row.length + 1

        @x = current_row.length + 1
      when DOWN
        return if current_row.nil?

        # Keep cursor at the beginning of the line when on the last line
        @x = 1 if @y == @text_content.length
        # Move cursor to the next line as long as we're not at the end of the file
        @y += 1 if @y <= @text_content.length

        current_row = @text_content[@y - 1]
        return if current_row.nil?
        # Keep the cursor at the same column position if the new current line is longer
        return if @x <= current_row.length + 1

        @x = current_row.length + 1
      when RIGHT
        return if current_row.nil?

        if @x > current_row.length
          # Move to the beginning of the next line as long as we're not at the end of the file
          if @y <= @text_content.length + 1
            @x = 1
            @y += 1
          end
        else
          @x += 1
        end
      when LEFT
        return if @x == 1 && @y == 1

        if @x == 1
          @y -= 1
          current_row = @text_content[@y - 1]
          @x = current_row.length + 1
        else
          @x -= 1
        end
      end
    end
  else
    if current_row.nil?
      current_row = String.new
      @text_content[@y - 1] = current_row
    end
    current_row.insert(@x - 1, content) # Insert at -1 on an empty string is fine
    @x += 1
  end
end
```

Finally, let's add support for the enter key. According to the [ASCII table][ascii-table], the ordinal value for Enter is

```ruby
def process_keypress(content)
  current_row = @text_content[@y - 1]

  if content == 'Q'
    exit(0)
  elsif content.ord == BACKSPACE
    return if @x == 1 && @y == 1

    if @x == 1
      if current_row.nil? || current_row.empty?
        @text_content.delete_at(@y - 1)
        @y -= 1
        current_row = @text_content[@y - 1]
        @x = current_row.length + 1
      else
        previous_row = @text_content[@y - 2]
        @x = previous_row.length + 1
        @text_content[@y - 2] = previous_row + current_row
        @text_content.delete_at(@y - 1)
        @y -= 1
      end
    else
      deletion_index = @x - 2
      current_row.slice!(deletion_index)
      @x -= 1
    end
  elsif content.ord == ESC
    # ...
  elsif content.ord == ENTER
    carry = if current_row && current_row.length > (@x - 1)
              current_row.slice!((@x - 1)..-1)
            else
              String.new
            end
    new_line_index = if @y - 1 == @text_content.length # We're on a new line at the end
                        @y - 1
                      else
                        @y
                      end
    @text_content.insert(new_line_index, carry)
    @x = 1
    @y += 1
  else
    if current_row.nil?
      current_row = String.new
      @text_content[@y - 1] = current_row
    end
    current_row.insert(@x - 1, content) # Insert at -1 on an empty string is fine
    @x += 1
  end
end
```

---

## Conclusion

What we build is very simple, and only a subset of what kilo does, in future posts we'll add

Putting it all together:

```ruby
require "io/console"

class Editor
  PRINTABLE_ASCII_RANGE = 32..126
  HIDE_CURSOR = "\x1b[?25l"
  SHOW_CURSOR = "\x1b[?25h"
  HOME = "\x1b[H"
  CLEAR = "\x1b[0K"
  DEFAULT_FOREGROUND_COLOR = "\x1b[39m"
  BACKSPACE = 127
  ENTER = 13
  ESC = 27
  OPENING_SQUARE_BRACKET = "["
  UP = "A"
  DOWN = "B"
  RIGHT = "C"
  LEFT = "D"

  def initialize
    @text_content = [String.new]
    @x = 1
    @y = 1
    @height, @width = IO.console.winsize
  end

  def start
    IO.console.raw do
      loop do
        refresh
        content = STDIN.readpartial(1)
        log_to_stderr("read: '#{content}'")
        log_to_stderr("read: '#{content.ord}'")
        process_keypress(content)
      end
    end
  end

  private

  def log_to_stderr(msg)
    return if STDERR.tty? # true when not redirecting to a file, a little janky but works for what I want

    STDERR.puts(msg)
  end

  def coordinates
    "\x1b[#{ @y };#{ @x }H"
  end

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
      append_buffer << DEFAULT_FOREGROUND_COLOR
      append_buffer << CLEAR
      append_buffer << "\r\n"
    end

    append_buffer.strip!
    append_buffer << HOME
    append_buffer << coordinates
    append_buffer << SHOW_CURSOR
    log_to_stderr("'#{ append_buffer }'".inspect)
    log_to_stderr("Cursor postition: x: #{ @x }, y: #{ @y }: #{ @y };#{ @x }H")

    STDOUT.write(append_buffer)
  end

  def process_keypress(content)
    current_row = @text_content[@y - 1]

    if content == 'Q'
      exit(0)
    elsif content.ord == BACKSPACE
      return if @x == 1 && @y == 1

      if @x == 1
        if current_row.nil? || current_row.empty?
          @text_content.delete_at(@y - 1)
          @y -= 1
          current_row = @text_content[@y - 1]
          @x = current_row.length + 1
        else
          previous_row = @text_content[@y - 2]
          @x = previous_row.length + 1
          @text_content[@y - 2] = previous_row + current_row
          @text_content.delete_at(@y - 1)
          @y -= 1
        end
      else
        deletion_index = @x - 2
        current_row.slice!(deletion_index)
        @x -= 1
      end
    elsif content.ord == ESC
      second_char = STDIN.read_nonblock(1, exception: false)
      return if second_char == :wait_readable

      third_char = STDIN.read_nonblock(1, exception: false)
      return if third_char == :wait_readable

      if second_char.ord == OPENING_SQUARE_BRACKET.ord
        case third_char
        when UP
          return if current_row.nil?

          @y -= 1 if @y > 1

          current_row = @text_content[@y - 1]
          return if current_row.nil?
          # Keep the cursor at the same column position if the new current line is longer
          return if @x <= current_row.length + 1

          @x = current_row.length + 1
        when DOWN
          return if current_row.nil?

          # Keep cursor at the beginning of the line when on the last line
          @x = 1 if @y == @text_content.length
          # Move cursor to the next line as long as we're not at the end of the file
          @y += 1 if @y <= @text_content.length

          current_row = @text_content[@y - 1]
          return if current_row.nil?
          # Keep the cursor at the same column position if the new current line is longer
          return if @x <= current_row.length + 1

          @x = current_row.length + 1
        when RIGHT
          return if current_row.nil?

          if @x > current_row.length
            # Move to the beginning of the next line as long as we're not at the end of the file
            if @y <= @text_content.length + 1
              @x = 1
              @y += 1
            end
          else
            @x += 1
          end
        when LEFT
          return if @x == 1 && @y == 1

          if @x == 1
            @y -= 1
            current_row = @text_content[@y - 1]
            @x = current_row.length + 1
          else
            @x -= 1
          end
        end
      end
    elsif content.ord == ENTER
      carry = if current_row && current_row.length > (@x - 1)
                current_row.slice!((@x - 1)..-1)
              else
                String.new
              end
      new_line_index = if @y - 1 == @text_content.length # We're on a new line at the end
                         @y - 1
                       else
                         @y
                       end
      @text_content.insert(new_line_index, carry)
      @x = 1
      @y += 1
    else
      if current_row.nil?
        current_row = String.new
        @text_content[@y - 1] = current_row
      end
      current_row.insert(@x - 1, content) # Insert at -1 on an empty string is fine
      @x += 1
    end
  end
end

Editor.new.start
```

[kilo-github]:https://github.com/antirez/kilo
[io-console-gem-ruby]:https://github.com/ruby/ruby/tree/master/ext/io/console
[io-console-gem]:https://github.com/ruby/io-console
[ansi-escape-codes]:https://en.wikipedia.org/wiki/ANSI_escape_code
[burke-libbey-codes]:https://notes.burke.libbey.me/ansi-escape-codes/
[ruby-ord-method]:https://ruby-doc.org/3.3.6/String.html#method-i-oct
[ascii-table]:https://www.asciitable.com/

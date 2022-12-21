require 'termios'

s = [0, 0, 0, 0].pack("S_S_S_S_")
STDOUT.ioctl(Termios::TIOCGWINSZ, s)

HEIGHT, WIDTH, _, _ = s.unpack("S_S_S_S_")

TEXT_CONTENT = [""]

current = Termios.tcgetattr(STDIN)
t = current.dup
# https://man7.org/linux/man-pages/man3/tcflush.3.html
t.c_lflag &= ~(Termios::ICANON)
# t.c_iflag &= ~(Termios::BRKINT | Termios::ICRNL | Termios::INPCK | Termios::ISTRIP | Termios::IXON)
# t.c_oflag &= ~(Termios::OPOST)
# t.c_cflag |= (Termios::CS8)
# t.c_lflag &= ~(Termios::ECHO | Termios::ICANON | Termios::IEXTEN | Termios::ISIG)
# t.c_cc[Termios::VMIN] = 1 # Setting 0 as in Kilo raises EOF errors
Termios.tcsetattr(STDIN, Termios::TCSANOW, t)

def refresh
  append_buffer = ""
  append_buffer << "\x1b[?25l" # Hide cursor
  append_buffer << "\x1b[H"
  HEIGHT.times do |row_index|    
    if row_index >= TEXT_CONTENT.count
      append_buffer << "~\x1b[0K\r\n"
      next
    end
  end
  append_buffer << "\x1b[H"
  append_buffer << TEXT_CONTENT.join("")
  append_buffer << "\x1b[?25h" # Show cursor
  STDOUT.write(append_buffer)
end

loop do
  # refresh
  c = STDIN.readpartial(1)
  exit(0) if c == "q"

  # if c.ord >= 32 && c.ord <= 126
  #   TEXT_CONTENT.last << c
  # end
end

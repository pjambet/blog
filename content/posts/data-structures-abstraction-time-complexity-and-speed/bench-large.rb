require 'benchmark/ips'
require 'set'

def bench(size)
  Benchmark.ips do |x|

    set = Set.new
    array = []
    hash = {}
    size.times do |i|
      set.add(i)
      array << i
      hash[i] = i
    end

    x.report("Set (#{size})") do
      set.include?(1)
      set.include?(2)
      set.include?(3)
      set.include?(0)
      set.include?(9999)
    end
    x.report("Array (#{size})") do
      array.include?(1)
      array.include?(2)
      array.include?(3)
      array.include?(0)
      array.include?(9999)
    end
    x.report("Hash (#{size})") do
      hash.include?(1)
      hash.include?(2)
      hash.include?(3)
      hash.include?(0)
      hash.include?(9999)
    end

    x.compare!
  end
end

bench(3)
bench(1_000_000)

package main

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"strings"
	// "reflect"
	// "syscall"
)

func handleConnection(c net.Conn) {
	fmt.Printf("Serving %s\n", c.RemoteAddr().String())

	for {
		netData, err := bufio.NewReader(c).ReadString('\n')
		if err != nil {
			fmt.Println("error reading:", err)
			break
		}

		temp := strings.TrimSpace(netData)
		fmt.Println("Received:", temp)
	}

	c.Close()
}

func main() {
	arguments := os.Args
	if len(arguments) == 1 {
		fmt.Println("Please provide a port number!")
		return
	}

	PORT := ":" + arguments[1]
	laddr, err := net.ResolveTCPAddr("tcp", "127.0.0.1" + PORT)
	l, err := net.ListenTCP("tcp4", laddr)
	// var rset syscall.FdSet
	// v := reflect.ValueOf(l)
	// netFD := reflect.Indirect(reflect.Indirect(v).FieldByName("fd"))
	// fd := int(netFD.FieldByName("sysfd").Int())
	// rset.Set(1)
	// f := l.(FileInterface).File()
	// fmt.Println("l, netFD, fd:", v, netFD, rset, l.File())
	file, err := l.File()
	fmt.Println("l:", file.Fd())
	if err != nil {
		fmt.Println(err)
		return
	}
	defer l.Close()

	for {
		c, err := l.Accept()
		if err != nil {
			fmt.Println(err)
			return
		}
		go handleConnection(c)
	}
}

package main

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"strings"
)

func handleConnection(client net.Conn) {
	fmt.Printf("Serving %s\n", client.RemoteAddr().String())

	for {
		netData, err := bufio.NewReader(client).ReadString('\n')
		if err != nil {
			fmt.Println("error reading:", err)
			break
		}

		temp := strings.TrimSpace(netData)
		fmt.Println("Received:", temp)
		client.Write([]byte(temp + "\n"))
	}

	fmt.Println("Closing client")
	client.Close()
}

func main() {
	arguments := os.Args
	if len(arguments) == 1 {
		fmt.Println("Please provide a port number!")
		return
	}

	port := ":" + arguments[1]
	server, err := net.Listen("tcp4", port)
	if err != nil {
		fmt.Println(err)
		return
	}
	defer server.Close()

	for {
		client, err := server.Accept()
		if err != nil {
			fmt.Println(err)
			return
		}
		go handleConnection(client)
	}
}

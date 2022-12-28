package main

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
)

const MIN = 1
const MAX = 100

func handleConnection(db map[string]string, client net.Conn) {
	defer client.Close()

	for {
		netData, err := bufio.NewReader(client).ReadString('\n')
		if err != nil {
			fmt.Println("error reading:", err)
			return
		}

		var response string
		commandString := strings.TrimSpace(netData)
		parts := strings.Split(commandString, " ")
		command := parts[0]

		switch command {
		case "STOP", "QUIT":
			return
		case "GET":
			if len(parts) > 1 {
				key := parts[1]

				response = db[key]
			} else {
				response = "ERR wrong number of arguments for 'get' command"
			}
		case "SET":
			if len(parts) > 2 {
				key := parts[1]
				value := parts[2]

				db[key] = value
				response = "OK"
			} else {
				response = "ERR wrong number of arguments for 'set' command"
			}
		case "INCR":
			if len(parts) > 1 {
				key := parts[1]
				value, ok := db[key]

				if ok {
					intValue, err := strconv.Atoi(value)
					if err != nil {
						response = "ERR value is not an integer or out of range"
					} else {
						response = strconv.Itoa(intValue + 1)
						db[key] = response
					}
				} else {
					response = "1"
					db[key] = response
				}
			} else {
				response = "ERR wrong number of arguments for 'incr' command"
			}
		case "DEL":
			if len(parts) > 1 {
				key := parts[1]
				_, ok := db[key]

				if ok {
					delete(db, key)
					response = "1"
				} else {
					response = "0"
				}
			} else {
				response = "ERR wrong number of arguments for 'del' command"
			}
		default:
			response = "ERR unknown command"
		}

		client.Write([]byte(response + "\n"))
	}
}

func main() {
	arguments := os.Args
	if len(arguments) == 1 {
		fmt.Println("Please provide a port number!")
		return
	}

	PORT := ":" + arguments[1]
	server, err := net.Listen("tcp4", PORT)
	if err != nil {
		fmt.Println(err)
		return
	}
	defer server.Close()

	db := make(map[string]string)

	for {
		client, err := server.Accept()
		if err != nil {
			fmt.Println(err)
			return
		}
		go handleConnection(db, client)
	}
}

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RPO表情APP测试服务器
用于在电脑上模拟roboto_origin主控，测试TCP通信功能

使用方法：
1. 电脑和手机连接同一WiFi
2. 运行本脚本: python test_server.py
3. 修改APP中Constants.DEFAULT_MASTER_IP为本机IP
4. 运行APP，观察连接和指令收发
"""

import socket
import json
import threading
import time

HOST = '0.0.0.0'
PORT = 8899

clients = []
seq = 0

def handle_client(client_socket, addr):
    print(f"[+] 新连接: {addr}")
    clients.append(client_socket)
    
    buffer = ""
    try:
        while True:
            data = client_socket.recv(1024)
            if not data:
                break
            
            buffer += data.decode('utf-8')
            while '\n' in buffer:
                line, buffer = buffer.split('\n', 1)
                line = line.strip()
                if line:
                    try:
                        cmd = json.loads(line)
                        print(f"[←] 收到来自{addr}: {cmd.get('cmd')}")
                        
                        # 自动回复ping
                        if cmd.get('cmd') == 'ping':
                            pong = json.dumps({
                                "cmd": "pong",
                                "timestamp": int(time.time() * 1000),
                                "seq": cmd.get('seq', 0),
                                "source": "test_server"
                            }) + '\n'
                            client_socket.send(pong.encode('utf-8'))
                    except json.JSONDecodeError:
                        print(f"[!] 无效JSON: {line}")
    except Exception as e:
        print(f"[-] 连接错误 {addr}: {e}")
    finally:
        print(f"[-] 断开连接: {addr}")
        clients.remove(client_socket)
        client_socket.close()

def send_command(cmd):
    """向所有客户端发送指令"""
    global seq
    seq += 1
    cmd['timestamp'] = int(time.time() * 1000)
    cmd['seq'] = seq
    data = json.dumps(cmd) + '\n'
    
    for client in clients[:]:
        try:
            client.send(data.encode('utf-8'))
            print(f"[→] 发送: {cmd.get('cmd')} {cmd.get('type', '')}")
        except:
            pass

def input_loop():
    """用户输入循环"""
    print("\n=== 测试指令菜单 ===")
    print("1 - 待机")
    print("2 - 说话")
    print("3 - 思考")
    print("4 - 识别中")
    print("5 - 应答")
    print("6 - 眨眼")
    print("7 - 播放Demo序列")
    print("8 - 清除回到待机")
    print("q - 退出")
    
    while True:
        cmd = input("\n输入指令: ").strip()
        
        if cmd == 'q':
            break
        elif cmd == '1':
            send_command({"cmd": "expression", "type": "idle", "force": True, "source": "test"})
        elif cmd == '2':
            send_command({"cmd": "expression", "type": "speaking", "force": True, "source": "test"})
        elif cmd == '3':
            send_command({"cmd": "expression", "type": "thinking", "force": True, "source": "test"})
        elif cmd == '4':
            send_command({"cmd": "expression", "type": "recognizing", "force": True, "source": "test"})
        elif cmd == '5':
            send_command({"cmd": "expression", "type": "response", "force": True, "source": "test"})
        elif cmd == '6':
            send_command({"cmd": "expression", "type": "blink", "force": True, "source": "test"})
        elif cmd == '7':
            send_command({
                "cmd": "sequence",
                "source": "test",
                "data": {
                    "types": ["recognizing", "response", "thinking", "speaking", "response"]
                }
            })
        elif cmd == '8':
            send_command({"cmd": "clear", "source": "test"})
        else:
            print("未知指令")

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(5)
    print(f"[*] 测试服务器监听 {HOST}:{PORT}")
    print(f"[*] 等待APP连接...")
    
    # 启动输入线程
    threading.Thread(target=input_loop, daemon=True).start()
    
    try:
        while True:
            client, addr = server.accept()
            threading.Thread(target=handle_client, args=(client, addr), daemon=True).start()
    except KeyboardInterrupt:
        print("\n[*] 服务器关闭")
    finally:
        server.close()

if __name__ == '__main__':
    main()

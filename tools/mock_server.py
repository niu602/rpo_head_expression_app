#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RPO表情APP模拟主控服务端
用于本地测试APP通信功能，模拟roboto_origin发送指令
使用方法：python mock_server.py
"""
import socket
import json
import threading
import time
from datetime import datetime

HOST = '0.0.0.0'
PORT = 8899

clients = []
running = True

def handle_client(client_socket, addr):
    """处理客户端连接"""
    print(f"[+] APP已连接: {addr}")
    clients.append(client_socket)
    buffer = ""
    
    try:
        while running:
            data = client_socket.recv(1024)
            if not data:
                break
            buffer += data.decode('utf-8')
            
            # 按行解析JSON
            while '\n' in buffer:
                line, buffer = buffer.split('\n', 1)
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                    print(f"[{datetime.now().strftime('%H:%M:%S')}] 收到APP消息: {msg.get('cmd')}")
                    
                    # 自动回复心跳
                    if msg.get('cmd') == 'ping':
                        pong = json.dumps({
                            "cmd": "pong",
                            "timestamp": int(time.time() * 1000),
                            "seq": msg.get('seq', 0)
                        }) + '\n'
                        client_socket.send(pong.encode('utf-8'))
                        print(f"[{datetime.now().strftime('%H:%M:%S')}] 回复心跳pong")
                except json.JSONDecodeError:
                    print(f"[!] 收到非JSON消息: {line}")
    except Exception as e:
        print(f"[-] 连接断开: {e}")
    finally:
        if client_socket in clients:
            clients.remove(client_socket)
        client_socket.close()
        print(f"[-] APP已断开: {addr}")

def send_command(cmd_type, duration=None, force=False):
    """发送表情指令给所有连接的APP"""
    if not clients:
        print("[!] 没有APP连接，无法发送指令")
        return
    
    cmd = {
        "cmd": "expression",
        "type": cmd_type,
        "timestamp": int(time.time() * 1000),
        "seq": int(time.time() * 1000) % 10000,
        "source": "mock_server",
        "force": force
    }
    if duration is not None:
        cmd["duration"] = duration
    
    msg = json.dumps(cmd) + '\n'
    for client in clients:
        try:
            client.send(msg.encode('utf-8'))
            print(f"[{datetime.now().strftime('%H:%M:%S')}] 发送指令: {cmd_type}")
        except Exception as e:
            print(f"[!] 发送失败: {e}")

def send_demo_sequence():
    """发送演示序列"""
    if not clients:
        print("[!] 没有APP连接，无法发送指令")
        return
    # 按顺序发送演示表情
    send_command("thinking", 1500, force=True)
    time.sleep(1.5)
    send_command("recognizing", 2000, force=True)
    time.sleep(2)
    send_command("response", 1000, force=True)
    time.sleep(1)
    send_command("speaking", 3000, force=True)
    time.sleep(3)
    send_command("idle", force=True)

def input_loop():
    """命令行输入循环"""
    print("\n=== 模拟主控命令行 ===")
    print("可用指令:")
    print("  idle/blink/speaking/thinking/recognizing/response - 切换对应表情")
    print("  demo - 播放标准演示序列")
    print("  clear - 回到待机")
    print("  quit/exit - 退出服务端")
    print("=====================\n")

    global running
    while running:
        try:
            cmd = input("> ").strip().lower()
            if not cmd:
                continue
            if cmd in ['quit', 'exit']:

                running = False
                break
            elif cmd == 'demo':
                threading.Thread(target=send_demo_sequence, daemon=True).start()
            elif cmd == 'clear':
                send_command("idle", force=True)
            elif cmd in ['idle', 'blink', 'speaking', 'thinking', 'recognizing', 'response']:
                send_command(cmd)
            else:
                print(f"[?] 未知指令: {cmd}")
        except KeyboardInterrupt:
            running = False
            break

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(5)
    server.settimeout(1)
    print(f"[*] 模拟主控服务端已启动，监听 {HOST}:{PORT}")
    print(f"[*] 等待APP连接...")
    
    # 启动输入线程
    input_thread = threading.Thread(target=input_loop, daemon=True)
    input_thread.start()
    
    try:
        while running:
            try:
                client, addr = server.accept()
                client_thread = threading.Thread(target=handle_client, args=(client, addr), daemon=True)
                client_thread.start()
            except socket.timeout:
                continue
    finally:
        print("\n[*] 服务端关闭")
        for client in clients:
            client.close()
        server.close()

if __name__ == "__main__":
    main()

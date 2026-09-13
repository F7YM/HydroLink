//go:build !windows && !linux

package main

// fatalDialog：其他平台暂无实现，错误只进日志。
func fatalDialog(title, msg string) {}

// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 float11
// 本文件改编自 JSLab (https://github.com/cciccicu/JSLab)，Copyright (C) 2026 cciccicu，
// 原作品以 GNU General Public License v3.0 发布；本文件同样以 GPL-3.0-only 发布。
//
// HydroKit 响应式增量发布器 (uiPublisher.js)
// 借鉴 JSLab 原生响应式增量补丁机制：
// 对比上一次编译节点与当前 ViewModel 中的 observed 数组，执行属性级原地变更 (In-place patch)，
// 避免快应用直接重造数组引发原生 DOM 重新实例化与闪烁，极大降低 GC 压力与内存峰值。

function copyNode(node) {
  const copy = {};
  for (const key in node) {
    if (key !== 'autoLines' && key !== 'node') copy[key] = node[key];
  }
  return copy;
}

function patchNode(target, next, before) {
  if (next === before) return;
  // 1. 属性变更与新增
  for (const key in next) {
    if (key !== 'autoLines' && key !== 'node' && next[key] !== before[key]) {
      target[key] = next[key];
    }
  }
  // 2. 移除旧属性
  for (const key in before) {
    if (key !== 'autoLines' && key !== 'node' && next[key] === undefined && target[key] !== undefined) {
      delete target[key];
    }
  }
}

export function createUiPublisher() {
  let previous = [];

  return {
    /**
     * @param {Array} nodes 编译器产出的全新扁平节点列表
     * @param {Array} observed 当前绑定在 ViewModel (如 this.flatNodes) 的响应式数组
     * @returns {Array} 最小化变动后的响应式数组
     */
    update(nodes, observed) {
      if (!Array.isArray(nodes)) return [];
      if (!Array.isArray(observed)) observed = [];
      if (nodes === previous) return observed;

      const aligned = observed.length === previous.length;
      let stable = aligned && nodes.length === previous.length;

      if (stable) {
        for (let i = 0; i < nodes.length; i += 1) {
          if (nodes[i].id !== previous[i].id || nodes[i].kind !== previous[i].kind) {
            stable = false;
            break;
          }
        }
      }

      let result = observed;

      if (stable) {
        // 节点数量与类型完全一致（高频场景：文本刷新、状态改变、时钟跳动）
        // 保持 observed 数组引用完全不动，仅进行属性级原地赋值
        for (let i = 0; i < nodes.length; i += 1) {
          if (nodes[i] !== previous[i]) {
            patchNode(observed[i], nodes[i], previous[i]);
          }
        }
      } else {
        // 节点增删或重排序：根据 ID 尽可能复用旧节点实例
        const retained = Object.create(null);
        for (let i = 0; i < previous.length; i += 1) {
          if (previous[i] && previous[i].id != null) {
            retained[previous[i].id] = i;
          }
        }

        result = nodes.map(node => {
          const index = retained[node.id];
          if (index !== undefined && observed[index] && previous[index] && previous[index].kind === node.kind) {
            patchNode(observed[index], node, previous[index]);
            return observed[index];
          }
          return copyNode(node);
        });
      }

      previous = nodes;
      return result;
    },

    clear() {
      previous = [];
    }
  };
}

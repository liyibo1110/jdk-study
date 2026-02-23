package com.github.liyibo1110.jdk.java.util;

/**
 * 标记接口，用于List实现，表明其支持快速（通常为常数时间）随机访问。
 * 该接口主要目的是使泛型算法能够调整行为，在应用于随机访问List或顺序访问List时均能提供良好性能。
 *
 * 在处理随机访问列表（如ArrayList）时，最佳算法可能对顺序访问列表（如LinkedList）产生二次时间复杂度行为。
 * 通用列表算法应在应用算法前检查给定列表是否为该接口的实例，若发现该列表属于顺序访问列表（应用算法将导致性能低下），则需调整算法行为以确保性能达标。
 * @author liyibo
 * @date 2026-02-23 17:30
 */
public interface RandomAccess {

}

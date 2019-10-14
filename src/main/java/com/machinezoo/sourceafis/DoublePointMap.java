// Part of SourceAFIS: https://sourceafis.machinezoo.com
package com.machinezoo.sourceafis;

import java.nio.*;

class DoublePointMap {
	final int width;
	final int height;
	private final double[] array;
	DoublePointMap(int width, int height) {
		this.width = width;
		this.height = height;
		array = new double[2 * width * height];
	}
	DoublePointMap(IntPoint size) {
		this(size.x, size.y);
	}
	IntPoint size() {
		return new IntPoint(width, height);
	}
	DoublePoint get(int x, int y) {
		int i = offset(x, y);
		return new DoublePoint(array[i], array[i + 1]);
	}
	DoublePoint get(IntPoint at) {
		return get(at.x, at.y);
	}
	void set(int x, int y, double px, double py) {
		int i = offset(x, y);
		array[i] = px;
		array[i + 1] = py;
	}
	void set(int x, int y, DoublePoint point) {
		set(x, y, point.x, point.y);
	}
	void set(IntPoint at, DoublePoint point) {
		set(at.x, at.y, point);
	}
	void add(int x, int y, double px, double py) {
		int i = offset(x, y);
		array[i] += px;
		array[i + 1] += py;
	}
	void add(int x, int y, DoublePoint point) {
		add(x, y, point.x, point.y);
	}
	void add(IntPoint at, DoublePoint point) {
		add(at.x, at.y, point);
	}
	ByteBuffer serialize() {
		ByteBuffer buffer = ByteBuffer.allocate(Double.BYTES * array.length);
		buffer.asDoubleBuffer().put(array);
		buffer.flip();
		return buffer;
	}
	JsonArrayInfo json() {
		JsonArrayInfo info = new JsonArrayInfo();
		info.axes = new String[] { "y", "x", "axis" };
		info.dimensions = new int[] { height, width, 2 };
		info.scalar = "double";
		info.bitness = 64;
		info.endianness = "big";
		info.format = "IEEE754";
		return info;
	}
	private int offset(int x, int y) {
		return 2 * (y * width + x);
	}
}

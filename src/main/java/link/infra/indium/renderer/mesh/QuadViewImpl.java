/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package link.infra.indium.renderer.mesh;

import com.google.common.base.Preconditions;
import link.infra.indium.renderer.RenderMaterialImpl;
import link.infra.indium.renderer.helper.GeometryHelper;
import link.infra.indium.renderer.helper.NormalHelper;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3f;

import static link.infra.indium.renderer.mesh.EncodingFormat.*;

/**
 * Base class for all quads / quad makers. Handles the ugly bits
 * of maintaining and encoding the quad state.
 */
public class QuadViewImpl implements QuadView {
	protected Direction nominalFace;
	/** True when geometry flags or light face may not match geometry. */
	protected boolean isGeometryInvalid = true;
	protected final Vec3f faceNormal = new Vec3f();
	private boolean shade = true;

	/** Size and where it comes from will vary in subtypes. But in all cases quad is fully encoded to array. */
	protected int[] data;

	/** Beginning of the quad. Also the header index. */
	protected int baseIndex = 0;

	/**
	 * Use when subtype is "attached" to a pre-existing array.
	 * Sets data reference and index and decodes state from array.
	 */
	final void load(int[] data, int baseIndex) {
		this.data = data;
		this.baseIndex = baseIndex;
		load();
	}

	/**
	 * Like {@link #load(int[], int)} but assumes array and index already set.
	 * Only does the decoding part.
	 */
	public void load() {
		isGeometryInvalid = false;
		nominalFace = lightFace();

		// face normal isn't encoded
		NormalHelper.computeFaceNormal(faceNormal, this);
	}

	/** Reference to underlying array. Use with caution. Meant for fast renderer access */
	public int[] data() {
		return data;
	}

	public int normalFlags() {
		return EncodingFormat.normalFlags(data[baseIndex + HEADER_BITS]);
	}

	/** True if any vertex normal has been set. */
	public boolean hasVertexNormals() {
		return normalFlags() != 0;
	}

	/** gets flags used for lighting - lazily computed via {@link GeometryHelper#computeShapeFlags(QuadView)}. */
	public int geometryFlags() {
		computeGeometry();
		return EncodingFormat.geometryFlags(data[baseIndex + HEADER_BITS]);
	}

	protected void computeGeometry() {
		if (isGeometryInvalid) {
			isGeometryInvalid = false;

			NormalHelper.computeFaceNormal(faceNormal, this);

			// depends on face normal
			data[baseIndex + HEADER_BITS] = EncodingFormat.lightFace(data[baseIndex + HEADER_BITS], GeometryHelper.lightFace(this));

			// depends on light face
			data[baseIndex + HEADER_BITS] = EncodingFormat.geometryFlags(data[baseIndex + HEADER_BITS], GeometryHelper.computeShapeFlags(this));
		}
	}

	@Override
	public final void toVanilla(int textureIndex, int[] target, int targetIndex, boolean isItem) {
		System.arraycopy(data, baseIndex + VERTEX_X, target, targetIndex, QUAD_STRIDE);
	}

	@Override
	public final RenderMaterialImpl.Value material() {
		return EncodingFormat.material(data[baseIndex + HEADER_BITS]);
	}

	@Override
	public final int colorIndex() {
		return data[baseIndex + HEADER_COLOR_INDEX];
	}

	@Override
	public final int tag() {
		return data[baseIndex + HEADER_TAG];
	}

	@Override
	public final Direction lightFace() {
		computeGeometry();
		return EncodingFormat.lightFace(data[baseIndex + HEADER_BITS]);
	}

	@Override
	public final Direction cullFace() {
		return EncodingFormat.cullFace(data[baseIndex + HEADER_BITS]);
	}

	@Override
	public final Direction nominalFace() {
		return nominalFace;
	}

	@Override
	public final Vec3f faceNormal() {
		computeGeometry();
		return faceNormal;
	}

	@Override
	public void copyTo(MutableQuadView target) {
		computeGeometry();

		final MutableQuadViewImpl quad = (MutableQuadViewImpl) target;
		// copy everything except the material
		System.arraycopy(data, baseIndex + 1, quad.data, quad.baseIndex + 1, EncodingFormat.TOTAL_STRIDE - 1);
		quad.faceNormal.set(faceNormal.getX(), faceNormal.getY(), faceNormal.getZ());
		quad.nominalFace = this.nominalFace;
		quad.isGeometryInvalid = false;
	}

	@Override
	public Vec3f copyPos(int vertexIndex, Vec3f target) {
		if (target == null) {
			target = new Vec3f();
		}

		final int index = baseIndex + vertexIndex * VERTEX_STRIDE + VERTEX_X;
		target.set(Float.intBitsToFloat(data[index]), Float.intBitsToFloat(data[index + 1]), Float.intBitsToFloat(data[index + 2]));
		return target;
	}

	@Override
	public float posByIndex(int vertexIndex, int coordinateIndex) {
		return Float.intBitsToFloat(data[baseIndex + vertexIndex * VERTEX_STRIDE + VERTEX_X + coordinateIndex]);
	}

	@Override
	public float x(int vertexIndex) {
		return Float.intBitsToFloat(data[baseIndex + vertexIndex * VERTEX_STRIDE + VERTEX_X]);
	}

	@Override
	public float y(int vertexIndex) {
		return Float.intBitsToFloat(data[baseIndex + vertexIndex * VERTEX_STRIDE + VERTEX_Y]);
	}

	@Override
	public float z(int vertexIndex) {
		return Float.intBitsToFloat(data[baseIndex + vertexIndex * VERTEX_STRIDE + VERTEX_Z]);
	}

	@Override
	public boolean hasNormal(int vertexIndex) {
		return (normalFlags() & (1 << vertexIndex)) != 0;
	}

	protected final int normalIndex(int vertexIndex) {
		return baseIndex + vertexIndex * VERTEX_STRIDE + VERTEX_NORMAL;
	}

	@Override
	public Vec3f copyNormal(int vertexIndex, Vec3f target) {
		if (hasNormal(vertexIndex)) {
			if (target == null) {
				target = new Vec3f();
			}

			final int normal = data[normalIndex(vertexIndex)];
			target.set(NormalHelper.getPackedNormalComponent(normal, 0), NormalHelper.getPackedNormalComponent(normal, 1), NormalHelper.getPackedNormalComponent(normal, 2));
			return target;
		} else {
			return null;
		}
	}

	@Override
	public float normalX(int vertexIndex) {
		return hasNormal(vertexIndex) ? NormalHelper.getPackedNormalComponent(data[normalIndex(vertexIndex)], 0) : Float.NaN;
	}

	@Override
	public float normalY(int vertexIndex) {
		return hasNormal(vertexIndex) ? NormalHelper.getPackedNormalComponent(data[normalIndex(vertexIndex)], 1) : Float.NaN;
	}

	@Override
	public float normalZ(int vertexIndex) {
		return hasNormal(vertexIndex) ? NormalHelper.getPackedNormalComponent(data[normalIndex(vertexIndex)], 2) : Float.NaN;
	}

	@Override
	public int lightmap(int vertexIndex) {
		return data[baseIndex + vertexIndex * VERTEX_STRIDE + VERTEX_LIGHTMAP];
	}

	@Override
	public int spriteColor(int vertexIndex, int spriteIndex) {
		Preconditions.checkArgument(spriteIndex == 0, "Unsupported sprite index: %s", spriteIndex);

		return data[baseIndex + vertexIndex * VERTEX_STRIDE + VERTEX_COLOR];
	}

	@Override
	public float spriteU(int vertexIndex, int spriteIndex) {
		Preconditions.checkArgument(spriteIndex == 0, "Unsupported sprite index: %s", spriteIndex);

		return Float.intBitsToFloat(data[baseIndex + vertexIndex * VERTEX_STRIDE + VERTEX_U]);
	}

	@Override
	public float spriteV(int vertexIndex, int spriteIndex) {
		Preconditions.checkArgument(spriteIndex == 0, "Unsupported sprite index: %s", spriteIndex);

		return Float.intBitsToFloat(data[baseIndex + vertexIndex * VERTEX_STRIDE + VERTEX_V]);
	}

	public int vertexStart() {
		return baseIndex + HEADER_STRIDE;
	}

	public boolean hasShade() {
		return shade && !material().disableDiffuse(0);
	}

	public void shade(boolean shade) {
		this.shade = shade;
	}
}

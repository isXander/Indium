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

package link.infra.indium.renderer.render;

import link.infra.indium.renderer.IndiumRenderer;
import link.infra.indium.renderer.RenderMaterialImpl;
import link.infra.indium.renderer.helper.ColorHelper;
import link.infra.indium.renderer.mesh.EncodingFormat;
import link.infra.indium.renderer.mesh.MeshImpl;
import link.infra.indium.renderer.mesh.MutableQuadViewImpl;
import net.fabricmc.fabric.api.renderer.v1.material.BlendMode;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.model.ModelHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.render.*;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.render.model.json.ModelTransformation.Mode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3f;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The render context used for item rendering.
 * Does not implement emissive lighting for sake
 * of simplicity in the default renderer.
 */
public class ItemRenderContext extends MatrixRenderContext {
	/** Value vanilla uses for item rendering.  The only sensible choice, of course.  */
	private static final long ITEM_RANDOM_SEED = 42L;

	/** used to accept a method reference from the ItemRenderer. */
	@FunctionalInterface
	public interface VanillaQuadHandler {
		void accept(BakedModel model, ItemStack stack, int color, int overlay, MatrixStack matrixStack, VertexConsumer buffer);
	}

	private final ItemColors colorMap;
	private final Random random = new Random();
	private final Consumer<BakedModel> fallbackConsumer;
	private final Vec3f normalVec = new Vec3f();

	private MatrixStack matrixStack;
	private VertexConsumerProvider vertexConsumerProvider;
	private VertexConsumer modelVertexConsumer;
	private BlendMode quadBlendMode;
	private VertexConsumer quadVertexConsumer;
	private Mode transformMode;
	private int lightmap;
	private ItemStack itemStack;
	private VanillaQuadHandler vanillaHandler;

	private final Supplier<Random> randomSupplier = () -> {
		final Random result = random;
		result.setSeed(ITEM_RANDOM_SEED);
		return random;
	};

	private final int[] quadData = new int[EncodingFormat.TOTAL_STRIDE];

	public ItemRenderContext(ItemColors colorMap) {
		this.colorMap = colorMap;
		fallbackConsumer = this::fallbackConsumer;
	}

	public void renderModel(ItemStack itemStack, Mode transformMode, boolean invert, MatrixStack matrixStack, VertexConsumerProvider vertexConsumerProvider, int lightmap, int overlay, FabricBakedModel model, VanillaQuadHandler vanillaHandler) {
		this.lightmap = lightmap;
		this.overlay = overlay;
		this.itemStack = itemStack;
		this.vertexConsumerProvider = vertexConsumerProvider;
		this.matrixStack = matrixStack;
		this.transformMode = transformMode;
		this.vanillaHandler = vanillaHandler;
		quadBlendMode = BlendMode.DEFAULT;
		modelVertexConsumer = selectVertexConsumer(RenderLayers.getItemLayer(itemStack, transformMode != ModelTransformation.Mode.GROUND));

		matrixStack.push();
		((BakedModel) model).getTransformation().getTransformation(transformMode).apply(invert, matrixStack);
		matrixStack.translate(-0.5D, -0.5D, -0.5D);
		matrix = matrixStack.peek().getPositionMatrix();
		normalMatrix = matrixStack.peek().getNormalMatrix();

		model.emitItemQuads(itemStack, randomSupplier, this);

		matrixStack.pop();

		this.matrixStack = null;
		this.itemStack = null;
		this.vanillaHandler = null;
		modelVertexConsumer = null;
	}

	/**
	 * Use non-culling translucent material in GUI to match vanilla behavior. If the item
	 * is enchanted then also select a dual-output vertex consumer. For models with layered
	 * coplanar polygons this means we will render the glint more than once. Indium doesn't
	 * support sprite layers, so this can't be helped in this implementation.
	 */
	private VertexConsumer selectVertexConsumer(RenderLayer layerIn) {
		final RenderLayer layer = transformMode == ModelTransformation.Mode.GUI ? TexturedRenderLayers.getEntityTranslucentCull() : layerIn;
		return ItemRenderer.getArmorGlintConsumer(vertexConsumerProvider, layer, true, itemStack.hasGlint());
	}

	private class Maker extends MutableQuadViewImpl implements QuadEmitter {
		{
			data = quadData;
			clear();
		}

		@Override
		public Maker emit() {
			computeGeometry();
			renderQuad();
			clear();
			return this;
		}
	}

	private final Maker editorQuad = new Maker();

	private final Consumer<Mesh> meshConsumer = (mesh) -> {
		final MeshImpl m = (MeshImpl) mesh;
		final int[] data = m.data();
		final int limit = data.length;
		int index = 0;

		while (index < limit) {
			System.arraycopy(data, index, editorQuad.data(), 0, EncodingFormat.TOTAL_STRIDE);
			editorQuad.load();
			index += EncodingFormat.TOTAL_STRIDE;
			renderQuad();
		}
	};

	private int indexColor() {
		final int colorIndex = editorQuad.colorIndex();
		return colorIndex == -1 ? -1 : (colorMap.getColor(itemStack, colorIndex) | 0xFF000000);
	}

	private void renderQuad() {
		final MutableQuadViewImpl quad = editorQuad;

		if (!transform(editorQuad)) {
			return;
		}

		final RenderMaterialImpl.Value mat = quad.material();
		final int quadColor = mat.disableColorIndex(0) ? -1 : indexColor();
		final int lightmap = mat.emissive(0) ? BaseQuadRenderer.FULL_BRIGHTNESS : this.lightmap;

		for (int i = 0; i < 4; i++) {
			int c = quad.spriteColor(i, 0);
			c = ColorHelper.multiplyColor(quadColor, c);
			quad.spriteColor(i, 0, ColorHelper.swapRedBlueIfNeeded(c));
			quad.lightmap(i, ColorHelper.maxBrightness(quad.lightmap(i), lightmap));
		}

		VertexConsumerQuadBufferer.bufferQuad(quadVertexConsumer(mat.blendMode(0)), quad, matrix, overlay, normalMatrix, normalVec);
	}

	/**
	 * Caches custom blend mode / vertex consumers and mimics the logic
	 * in {@code RenderLayers.getEntityBlockLayer}. Layers other than
	 * translucent are mapped to cutout.
	 */
	private VertexConsumer quadVertexConsumer(BlendMode blendMode) {
		if (blendMode == BlendMode.DEFAULT) {
			return modelVertexConsumer;
		}

		if (blendMode != BlendMode.TRANSLUCENT) {
			blendMode = BlendMode.CUTOUT;
		}

		if (blendMode == quadBlendMode) {
			return quadVertexConsumer;
		} else if (blendMode == BlendMode.TRANSLUCENT) {
			quadVertexConsumer = selectVertexConsumer(TexturedRenderLayers.getEntityTranslucentCull());
			quadBlendMode = BlendMode.TRANSLUCENT;
		} else {
			quadVertexConsumer = selectVertexConsumer(TexturedRenderLayers.getEntityCutout());
			quadBlendMode = BlendMode.CUTOUT;
		}

		return quadVertexConsumer;
	}

	@Override
	public Consumer<Mesh> meshConsumer() {
		return meshConsumer;
	}

	private void fallbackConsumer(BakedModel model) {
		if (hasTransform()) {
			// if there's a transform in effect, convert to mesh-based quads so that we can apply it
			for (int i = 0; i <= ModelHelper.NULL_FACE_ID; i++) {
				random.setSeed(ITEM_RANDOM_SEED);
				final Direction cullFace = ModelHelper.faceFromIndex(i);
				renderFallbackWithTransform(model.getQuads((BlockState) null, cullFace, random), cullFace);
			}
		} else {
			vanillaHandler.accept(model, itemStack, lightmap, overlay, matrixStack, modelVertexConsumer);
		}
	}

	private void renderFallbackWithTransform(List<BakedQuad> quads, Direction cullFace) {
		if (quads.isEmpty()) {
			return;
		}

		final Maker editorQuad = this.editorQuad;

		for (final BakedQuad q : quads) {
			editorQuad.fromVanilla(q, IndiumRenderer.MATERIAL_STANDARD, cullFace);
			renderQuad();
		}
	}

	@Override
	public Consumer<BakedModel> fallbackConsumer() {
		return fallbackConsumer;
	}

	@Override
	public QuadEmitter getEmitter() {
		editorQuad.clear();
		return editorQuad;
	}
}

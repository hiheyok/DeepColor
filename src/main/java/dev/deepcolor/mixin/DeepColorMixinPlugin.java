package dev.deepcolor.mixin;

import dev.deepcolor.DeepColor;
import dev.deepcolor.core.Hooks;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Turns the whole mod off when rrtt217's HDR Mod is present, and reports injectors that did not land.
 * Injectors are require = 0, so the check is done here: after a mixin applies, every handler it
 * declares must be called from somewhere in the transformed target.
 *
 * <p>MixinExtras applies its wrappers later than postApply, so they cannot be seen in the bytecode
 * yet. For those the best available check is that the method they wrap exists.
 */
public final class DeepColorMixinPlugin implements IMixinConfigPlugin {
	private static final Set<String> INJECTORS = Set.of(
		"Lorg/spongepowered/asm/mixin/injection/Inject;",
		"Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
		"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;"
	);
	private static final Set<String> LATE_INJECTORS = Set.of(
		"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
		"Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;"
	);

	private boolean disabled;

	@Override
	public void onLoad(String mixinPackage) {
		this.disabled = DeepColor.isDisabledByHdrMod();
		if (this.disabled) {
			DeepColor.LOGGER.warn("HDR Mod (hdr_mod) is installed. It replaces the same render targets and swapchain, so Deep Color has disabled itself.");
		}
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return !this.disabled;
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		Set<String> called = new HashSet<>();
		for (MethodNode method : targetClass.methods) {
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && call.name.contains("deepcolor$")) {
					called.add(call.name);
				}
			}
		}
		for (MethodNode handler : mixinInfo.getClassNode(0).methods) {
			if (handler.visibleAnnotations == null) {
				continue;
			}
			for (AnnotationNode annotation : handler.visibleAnnotations) {
				boolean missing = INJECTORS.contains(annotation.desc) && called.stream().noneMatch(name -> name.endsWith(handler.name))
					|| LATE_INJECTORS.contains(annotation.desc) && !hasTargetMethod(annotation, targetClass);
				if (missing) {
					Hooks.failed(handler.name, targetClassName);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static boolean hasTargetMethod(AnnotationNode annotation, ClassNode targetClass) {
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if ("method".equals(annotation.values.get(i))) {
				for (String target : (List<String>) annotation.values.get(i + 1)) {
					String name = target.contains("(") ? target.substring(0, target.indexOf('(')) : target;
					if (targetClass.methods.stream().anyMatch(method -> method.name.equals(name))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}

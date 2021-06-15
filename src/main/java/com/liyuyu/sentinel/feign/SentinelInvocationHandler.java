package com.liyuyu.sentinel.feign;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import feign.Feign;
import feign.InvocationHandlerFactory.MethodHandler;
import feign.MethodMetadata;
import feign.Target;
import feign.hystrix.FallbackFactory;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static feign.Util.checkNotNull;

public class SentinelInvocationHandler implements InvocationHandler {

	private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(SentinelInvocationHandler.class);
	private final Target<?> target;
	private final Map<Method, MethodHandler> dispatch;
	private FallbackFactory fallbackFactory;
	private Map<Method, Method> fallbackMethodMap;

	SentinelInvocationHandler(Target<?> target, Map<Method, MethodHandler> dispatch, FallbackFactory fallbackFactory) {
		this.target = checkNotNull(target, "target");
		this.dispatch = checkNotNull(dispatch, "dispatch");
		this.fallbackFactory = fallbackFactory;
		this.fallbackMethodMap = toFallbackMethod(dispatch);
	}

	SentinelInvocationHandler(Target<?> target, Map<Method, MethodHandler> dispatch) {
		this.target = checkNotNull(target, "target");
		this.dispatch = checkNotNull(dispatch, "dispatch");
	}

	@Override
	public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
		if ("equals".equals(method.getName())) {
			try {
				Object otherHandler = args.length > 0 && args[0] != null ? Proxy.getInvocationHandler(args[0]) : null;
				return equals(otherHandler);
			} catch (IllegalArgumentException e) {
				return false;
			}
		} else if ("hashCode".equals(method.getName())) {
			return hashCode();
		} else if ("toString".equals(method.getName())) {
			return toString();
		}

		Object result;
		MethodHandler methodHandler = this.dispatch.get(method);
		if (target instanceof Target.HardCodedTarget) {
			// only handle by HardCodedTarget
			Target.HardCodedTarget hardCodedTarget = (Target.HardCodedTarget) target;
			MethodMetadata methodMetadata = SentinelContractHolder.METADATA_MAP.get(hardCodedTarget.type().getName() + Feign.configKey(hardCodedTarget.type(), method));
			if (methodMetadata == null) {
				result = methodHandler.invoke(args);
			}
			String resourceName = "feign:" + methodMetadata.template().method().toUpperCase() + ":" + hardCodedTarget.url() + methodMetadata.template().url();
			Entry entry = null;
			try {
				ContextUtil.enter(resourceName);
				entry = SphU.entry(resourceName, EntryType.OUT, 1, args);
				result = methodHandler.invoke(args);
			} catch (Throwable ex) {
				if (LOGGER.isInfoEnabled()) {
					LOGGER.info("Fallback due to: " + ex.getMessage(), ex);
				}
				if (!BlockException.isBlockException(ex)) {
					Tracer.trace(ex);
				}
				if (fallbackFactory == null) {
					throw ex;
				}
				try {
					return fallbackMethodMap.get(method).invoke(fallbackFactory.create(ex), args);
				} catch (IllegalAccessException e) {
					throw new AssertionError(e);
				} catch (InvocationTargetException e) {
					throw new AssertionError(e.getCause());
				}
			} finally {
				if (entry != null) {
					entry.exit(1, args);
				}
				ContextUtil.exit();
			}
		} else {
			// other target type using default strategy
			result = methodHandler.invoke(args);
		}

		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj instanceof SentinelInvocationHandler) {
			SentinelInvocationHandler other = (SentinelInvocationHandler) obj;
			return target.equals(other.target);
		}
		return false;
	}

	@Override
	public int hashCode() {
		return target.hashCode();
	}

	@Override
	public String toString() {
		return target.toString();
	}

	static Map<Method, Method> toFallbackMethod(Map<Method, MethodHandler> dispatch) {
		Map<Method, Method> result = new LinkedHashMap<>();
		for (Method method : dispatch.keySet()) {
			method.setAccessible(true);
			result.put(method, method);
		}
		return result;
	}

}

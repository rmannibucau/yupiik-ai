// Adapted from assistant-ui Elements (MIT). See ../UPSTREAM.md and ../LICENSE.assistant-ui.
import { useEffect, useLayoutEffect } from "react";

const hasWindow =
  typeof (globalThis as typeof globalThis & { window?: unknown }).window !==
  "undefined";

export const useIsomorphicLayoutEffect = hasWindow
  ? useLayoutEffect
  : useEffect;

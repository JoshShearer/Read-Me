// SPDX license-expression check for scripts/check-licenses.mjs (R-M13, AGENTS.md 14).
// Grammar: expr := term ("OR" term)* ; term := factor ("AND" factor)* ; factor := ID | "(" expr ")".
// Anything that does not parse, or names a license outside OSI, is not allowed: fail closed.
export const OSI = new Set([
  'MIT', 'ISC', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', '0BSD', 'BlueOak-1.0.0',
  'Unlicense', 'Python-2.0', 'Zlib', 'MPL-2.0',
]);

const ID = /^[A-Za-z0-9.+-]+$/;

export function allowed(expr) {
  const tokens = String(expr).replace(/[()]/g, ' $& ').trim().split(/\s+/).filter(Boolean);
  let i = 0;
  const fail = () => {
    throw new Error('unparseable');
  };
  function factor() {
    const t = tokens[i++];
    if (t === '(') {
      const v = orExpr();
      if (tokens[i++] !== ')') fail();
      return v;
    }
    if (t === undefined || t === ')' || t === 'AND' || t === 'OR' || !ID.test(t)) fail();
    return OSI.has(t);
  }
  function andExpr() {
    let v = factor();
    while (tokens[i] === 'AND') {
      i++;
      v = factor() && v;
    }
    return v;
  }
  function orExpr() {
    let v = andExpr();
    while (tokens[i] === 'OR') {
      i++;
      v = andExpr() || v;
    }
    return v;
  }
  try {
    const v = orExpr();
    return i === tokens.length && v;
  } catch {
    return false;
  }
}

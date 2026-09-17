// Catalog semantics cannot be expressed by JSON Schema's local shape constraints.
export function validateCatalogBindings(catalog) {
  const errors = [];
  const facts = new Map();
  for (const fact of catalog.facts) {
    if (facts.has(fact.path)) errors.push(`Duplicate fact path ${fact.path}`);
    facts.set(fact.path, fact);
  }
  for (const fact of catalog.facts) {
    if (fact.cardinality === 'MANY' && !fact.path.endsWith('[]')) errors.push(`Collection path required: ${fact.path}`);
    for (const key of fact.stableOrder ?? []) {
      const field = facts.get(`${fact.path}.${key}`);
      if (fact.cardinality !== 'MANY' || !field || field.cardinality !== 'ONE' || !field.required || field.nullable || field.type === 'OBJECT') {
        errors.push(`Invalid stableOrder ${fact.path}.${key}`);
      }
    }
  }
  const aliases = new Set();
  for (const binding of catalog.itemBindings) {
    const identity = `${binding.collectionPath}:${binding.localPath}`;
    const collection = facts.get(binding.collectionPath);
    const fact = facts.get(binding.factPath);
    const expectedPath = `${binding.collectionPath}.${binding.localPath.slice('item.'.length)}`;
    if (aliases.has(identity) || collection?.cardinality !== 'MANY' || !fact || fact.cardinality !== 'ONE' || binding.factPath !== expectedPath) {
      errors.push(`Invalid item binding ${identity} -> ${binding.factPath}`);
    }
    aliases.add(identity);
  }
  return errors;
}

export function resolveItemBinding(catalog, collectionPath, localPath) {
  const binding = catalog.itemBindings.find(b => b.collectionPath === collectionPath && b.localPath === localPath);
  if (!binding) throw new Error(`Unknown item binding ${collectionPath}:${localPath}`);
  const fact = catalog.facts.find(f => f.path === binding.factPath);
  if (!fact || binding.factPath !== `${collectionPath}.${localPath.slice('item.'.length)}`) {
    throw new Error(`Invalid item binding ${collectionPath}:${localPath}`);
  }
  return fact;
}

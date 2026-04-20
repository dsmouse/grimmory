import {RuleOperator} from '../component/magic-shelf-component';

export const MULTI_VALUE_OPERATORS: RuleOperator[] = [
  'includes_any',
  'includes_all',
  'excludes_all'
];

export const EMPTY_CHECK_OPERATORS: RuleOperator[] = [
  'is_empty',
  'is_not_empty'
];

export const RELATIVE_DATE_OPERATORS: RuleOperator[] = [
  'within_last',
  'older_than',
  'this_period'
];

function parseDate(val: unknown): Date | null {
  if (typeof val === "string") {
    if (val.match("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) {
      // If `val` is a date-only string, add midnight local time to it
      // when parsing so we don't get UTC time leading to confusion.
      val = Date.parse(val + 'T00:00:00');
    } else {
      val = Date.parse(val);
    }
  }

  if (typeof val === "number") {
    val = new Date(val);
  }

  if (!(val instanceof Date) || isNaN(val.getTime())) {
    return null;
  }

  return val;
}

export function parseValue(val: unknown, type: 'string' | 'number' | 'decimal' | 'date' | 'boolean' | undefined): unknown {
  if (val == null) return null;
  if (type === 'number' || type === 'decimal') {
    const num = Number(val);
    return isNaN(num) ? null : num;
  }
  if (type === 'date') {
    return parseDate(val)
  }
  return val;
}

export function removeNulls(obj: unknown): unknown {
  if (Array.isArray(obj)) {
    return obj.map(removeNulls);
  } else if (typeof obj === 'object' && obj !== null) {
    return Object.entries(obj).reduce((acc: Record<string, unknown>, [key, value]) => {
      const cleanedValue = removeNulls(value);
      if (cleanedValue !== null && cleanedValue !== undefined) {
        acc[key] = cleanedValue;
      }
      return acc;
    }, {});
  }
  return obj;
}

function serializeDate(val: unknown) {
  if (!(val instanceof Date)) {
    return val;
  }

  // Manually craft the serialized date as a "local" date string
  // so we don't have a timezone shifting the day-of-month.
  const year = ("0000" + val.getFullYear()).slice(-4);
  const month = ("00" + (val.getMonth() + 1)).slice(-2);
  const day = ("00" + (val.getDate())).slice(-2);
  return `${year}-${month}-${day}`;
}

export function serializeDateRules(ruleOrGroup: unknown): unknown {
  if (ruleOrGroup !== null && typeof ruleOrGroup === 'object' && 'rules' in ruleOrGroup) {
    const group = ruleOrGroup as { rules: unknown[] };
    return {
      ...(ruleOrGroup as Record<string, unknown>),
      rules: group.rules.map(serializeDateRules)
    };
  }

  const rule = ruleOrGroup as { field?: string; operator?: string; value?: unknown; valueStart?: unknown; valueEnd?: unknown; [key: string]: unknown };
  const isRelativeDateOp = RELATIVE_DATE_OPERATORS.includes(rule.operator as RuleOperator);
  const isDateField = !isRelativeDateOp && (rule.field === 'publishedDate' || rule.field === 'dateFinished' || rule.field === 'addedOn' || rule.field === 'lastReadTime');

  return {
    ...(ruleOrGroup as Record<string, unknown>),
    value: isDateField ? serializeDate(rule.value) : rule.value,
    valueStart: isDateField ? serializeDate(rule.valueStart) : rule.valueStart,
    valueEnd: isDateField ? serializeDate(rule.valueEnd) : rule.valueEnd
  };
}

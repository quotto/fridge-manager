import { IAspect, Stack } from 'aws-cdk-lib';
import { CfnManagedPolicy, CfnPolicy } from 'aws-cdk-lib/aws-iam';
import { IConstruct } from 'constructs';

interface PolicyStatementShape {
  readonly Action?: unknown;
  readonly NotAction?: unknown;
  readonly Resource?: unknown;
  readonly NotResource?: unknown;
}

interface PolicyDocumentShape {
  readonly Statement?: unknown;
}

function containsWildcard(value: unknown): boolean {
  if (typeof value === 'string') {
    return value === '*';
  }
  return Array.isArray(value) && value.some(containsWildcard);
}

function asStatements(value: unknown): readonly PolicyStatementShape[] {
  if (!value || typeof value !== 'object') {
    return [];
  }
  const document = value as PolicyDocumentShape;
  if (!document.Statement) {
    return [];
  }
  return (Array.isArray(document.Statement) ? document.Statement : [document.Statement]).filter(
    (statement): statement is PolicyStatementShape => Boolean(statement) && typeof statement === 'object',
  );
}

/** IAM identity policyに無制限の操作・リソースが入ることをsynth時に防ぐ。 */
export class LeastPrivilegeIamAspect implements IAspect {
  public visit(node: IConstruct): void {
    if (!(node instanceof CfnPolicy) && !(node instanceof CfnManagedPolicy)) {
      return;
    }

    const document = Stack.of(node).resolve(node.policyDocument);
    const violations = asStatements(document).filter(
      (statement) =>
        containsWildcard(statement.Action) ||
        containsWildcard(statement.NotAction) ||
        containsWildcard(statement.Resource) ||
        containsWildcard(statement.NotResource),
    );

    if (violations.length > 0) {
      throw new Error(
        `最小権限違反 (${node.node.path}): IAM identity policy の Action/Resource に単独ワイルドカードは指定できません`,
      );
    }
  }
}

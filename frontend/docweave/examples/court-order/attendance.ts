export type PartyKind = "claimant" | "defendant";

export type AttendanceChoice =
  | "counsel"
  | "solicitor"
  | "solicitors-agent"
  | "housing-officer"
  | "duty-adviser"
  | "in-person"
  | "letter"
  | "not-present";

export interface Attendance {
  partyKind: PartyKind;
  partyNumber: number;
  choice: AttendanceChoice;
  name?: string;
}

function ordinal(value: number): string {
  const lastTwoDigits = value % 100;
  if (lastTwoDigits >= 11 && lastTwoDigits <= 13) return `${value}th`;

  switch (value % 10) {
    case 1:
      return `${value}st`;
    case 2:
      return `${value}nd`;
    case 3:
      return `${value}rd`;
    default:
      return `${value}th`;
  }
}

export function attendanceDescription(
  attendance: Attendance,
): string | undefined {
  const party = attendance.partyKind === "claimant"
    ? "the claimant"
    : `the ${ordinal(attendance.partyNumber)} defendant`;

  switch (attendance.choice) {
    case "counsel":
      return `counsel for ${party}`;
    case "solicitor":
      return `solicitor for ${party}`;
    case "solicitors-agent":
      return `solicitor's agent for ${party}`;
    case "housing-officer":
      return `the housing officer on behalf of ${party}`;
    case "duty-adviser":
      return `the duty adviser on behalf of ${party}`;
    case "in-person":
      return attendance.partyKind === "claimant"
        ? "claimant acting in person"
        : `${party} acting in person`;
    case "letter":
      return `written representations on behalf of ${party}`;
    case "not-present":
      return undefined;
  }
}

export function buildAttendanceRegister(
  attendances: readonly Attendance[],
): string | undefined {
  const descriptions = attendances.flatMap((attendance) => {
    const description = attendanceDescription(attendance);
    if (!description) return [];

    const name = attendance.name?.trim();
    return name ? [`${name}, ${description}`] : [description];
  });

  if (descriptions.length === 0) return undefined;
  if (descriptions.length === 1) return descriptions[0];

  return `${descriptions.slice(0, -1).join(", ")} and ${descriptions.at(-1)}`;
}

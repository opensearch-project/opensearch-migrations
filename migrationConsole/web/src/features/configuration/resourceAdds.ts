export interface ResourceAddOption {
  id: string;
  label: string;
  disabled: boolean;
  disabledReason?: string;
}


export interface ResourceAddController {
  options: ResourceAddOption[];
  status: "loading" | "ready" | "unavailable";
  busy: boolean;
  add: (optionId: string) => void;
}

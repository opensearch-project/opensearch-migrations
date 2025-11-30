from datetime import datetime
from pydantic import BaseModel, Field, field_validator, field_serializer
from typing import Dict

from console_link.environment import Environment


class SessionBase(BaseModel):
    name: str
    model_config = {
        "arbitrary_types_allowed": True,
    }


class Session(SessionBase):
    created: datetime = Field(
        description="Start time in ISO 8601 format",
        json_schema_extra={"format": "date-time"}
    )
    updated: datetime = Field(
        description="Start time in ISO 8601 format",
        json_schema_extra={"format": "date-time"}
    )
    env: Environment | None

    @field_serializer('created', 'updated')
    def serialize_datetime(self, dt: datetime) -> str:
        return dt.isoformat()

    @field_validator('created', 'updated', mode='before')
    @classmethod
    def parse_datetime(cls, v):
        if isinstance(v, str):
            return datetime.fromisoformat(v)
        return v

    @field_serializer('env')
    def serialize_environment(self, env: Environment) -> Dict:
        return env.config

    @field_validator('env', mode='before')
    @classmethod
    def parse_environment(cls, v):
        if isinstance(v, Dict):
            return Environment(config=v)
        return v

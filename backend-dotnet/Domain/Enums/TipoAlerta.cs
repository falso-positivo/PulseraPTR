namespace Domain.Enums;

public enum TipoAlerta
{
    FrecuenciaCardiaca = 0,
    SosManual = 1,

    SosAutomatico = 2,

    /// <summary>Alerta generada por un evento de emergencia <see cref="TipoEventoEmergencia.CAIDA"/>.</summary>
    CaidaDetectada = 3
}

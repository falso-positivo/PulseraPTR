using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Infrastructure.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class AddPasosActividadToMediciones : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            // Idempotente: bases creadas antes de este fix pueden tener la columna agregada a mano.
            migrationBuilder.Sql(
                "IF COL_LENGTH('Mediciones', 'PasosActividad') IS NULL " +
                "ALTER TABLE [Mediciones] ADD [PasosActividad] int NULL;");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "PasosActividad",
                table: "Mediciones");
        }
    }
}
